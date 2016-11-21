/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildStatus;
import jenkins.util.Timer;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.cancelBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuildConfigUid;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.handleBuildList;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.triggerJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancellable;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancelled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isNew;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.WARNING;

public class BuildWatcher implements Watcher<Build> {
  private static final Logger logger = Logger.getLogger(BuildWatcher.class.getName());

  private final String namespace;
  private Watch buildsWatch;

  public BuildWatcher(String namespace) {
    this.namespace = namespace;
  }

  public void start() {
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        logger.info("loading initial Build resources");

        try {
          BuildList newBuilds = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.NEW).list();
          onInitialBuilds(newBuilds);
          logger.info("loaded initial Build resources");
          buildsWatch = getOpenShiftClient().builds().inNamespace(namespace).withResourceVersion(newBuilds.getMetadata().getResourceVersion()).watch(BuildWatcher.this);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
        }
      }
    };
    // lets give jenkins a while to get started ;)
    Timer.get().schedule(task, 500, TimeUnit.MILLISECONDS);
  }

  public void stop() {
    if (buildsWatch != null) {
      buildsWatch.close();
      buildsWatch = null;
    }
  }

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());

      if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
        stop();
        start();
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public void eventReceived(Action action, Build build) {
    try {
      switch (action) {
        case ADDED:
          buildAdded(build);
          break;
        case MODIFIED:
          buildModified(build);
          break;
      }
    } catch (Exception e) {
      logger.log(WARNING, "Caught: " + e, e);
    }
  }

  public synchronized static void onInitialBuilds(BuildList buildList) {
    List<Build> items = buildList.getItems();
    if (items != null) {

      Collections.sort(items, new Comparator<Build>() {
        @Override
        public int compare(Build b1, Build b2) {
          return Long.compare(
            Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
            Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER))
          );
        }
      });

      // We need to sort the builds into their build configs so we can handle build run policies correctly.
      Map<String, BuildConfig> buildConfigMap = new HashMap<>();
      Map<BuildConfig, List<Build>> buildConfigBuildMap = new HashMap<>(items.size());
      for (Build b : items) {
        String buildConfigName = b.getStatus().getConfig().getName();
        if (StringUtils.isEmpty(buildConfigName)) {
          continue;
        }
        String namespace = b.getMetadata().getNamespace();
        String bcMapKey = namespace + "/" + buildConfigName;
        BuildConfig bc = buildConfigMap.get(bcMapKey);
        if (bc == null) {
          bc = getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
          if (bc == null) {
            continue;
          }
          buildConfigMap.put(bcMapKey, bc);
        }
        List<Build> bcBuilds = buildConfigBuildMap.get(bc);
        if (bcBuilds == null) {
          bcBuilds = new ArrayList<>();
          buildConfigBuildMap.put(bc, bcBuilds);
        }
        bcBuilds.add(b);
      }

      // Now handle the builds.
      for (Map.Entry<BuildConfig, List<Build>> buildConfigBuilds : buildConfigBuildMap.entrySet()) {
        BuildConfig bc = buildConfigBuilds.getKey();
        if (bc.getMetadata() == null) {
          // Should never happen but let's be safe...
          continue;
        }
        WorkflowJob job = getJobFromBuildConfigUid(bc.getMetadata().getUid());
        if (job == null) {
          continue;
        }
        BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
        if (bcp == null) {
          continue;
        }
        List<Build> builds = buildConfigBuilds.getValue();
        handleBuildList(job, builds, bcp);
      }
    }
  }

  private static synchronized void buildModified(Build build) {
    BuildStatus status = build.getStatus();
    if (status != null &&
      isCancellable(status) &&
      isCancelled(status)) {
      WorkflowJob job = getJobFromBuild(build);
      if (job != null) {
        cancelBuild(job, build);
      }
    }
  }

  public static synchronized void buildAdded(Build build) throws IOException {
    BuildStatus status = build.getStatus();
    if (status != null) {
      if (isCancelled(status)) {
        updateOpenShiftBuildPhase(build, CANCELLED);
        return;
      }
      if (!isNew(status)) {
        return;
      }
    }

    WorkflowJob job = getJobFromBuild(build);
    if (job != null) {
      triggerJob(job, build);
    }
  }

  public static synchronized void maybeScheduleNext(WorkflowJob job) {
    BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
    if (bcp == null) {
      return;
    }
    List<Build> builds = getOpenShiftClient().builds().inNamespace(bcp.getNamespace())
      .withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW).withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, bcp.getName()).list().getItems();
    Collections.sort(builds, new Comparator<Build>() {
      @Override
      public int compare(Build b1, Build b2) {
        return Long.compare(
          Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
          Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER))
        );
      }
    });

    for (Build b : builds) {
      try {
        buildAdded(b);
      } catch (IOException e) {
        logger.log(WARNING, "Failed to handle new build request", e);
      }
    }
  }

}
