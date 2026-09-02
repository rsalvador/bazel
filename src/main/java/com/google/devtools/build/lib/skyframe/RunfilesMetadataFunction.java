// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesMetadataValue;
import com.google.devtools.build.lib.collect.nestedset.RunfilesMetadataKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;

/** Requests and retains only direct metadata and shared child metadata nodes. */
public final class RunfilesMetadataFunction implements SkyFunction {
  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    RunfilesMetadataKey key = (RunfilesMetadataKey) skyKey;
    ImmutableList<Artifact> directArtifacts = key.directArtifacts();
    ImmutableList<RunfilesMetadataKey> childKeys = key.childKeys();
    ImmutableList<SkyKey> dependencies = ImmutableList.<SkyKey>builder()
        .addAll(Artifact.keys(directArtifacts)).addAll(childKeys).build();
    SkyframeLookupResult values = env.getValuesAndExceptions(dependencies);
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableList.Builder<Artifact> files = ImmutableList.builder();
    ImmutableList.Builder<FileArtifactValue> fileValues = ImmutableList.builder();
    ImmutableList.Builder<Artifact> trees = ImmutableList.builder();
    ImmutableList.Builder<TreeArtifactValue> treeValues = ImmutableList.builder();
    ImmutableList.Builder<RunfilesMetadataValue> children = ImmutableList.builder();
    for (Artifact input : directArtifacts) {
      SkyValue value = values.get(Artifact.key(input));
      if (value == null) {
        return null;
      }
      if (value instanceof FileArtifactValue fileValue) {
        files.add(input);
        fileValues.add(fileValue);
      } else if (value instanceof ActionExecutionValue actionValue) {
        files.add(input);
        fileValues.add(actionValue.getExistingFileArtifactValue(input));
      } else if (value instanceof TreeArtifactValue treeValue) {
        trees.add(input);
        treeValues.add(treeValue);
      } else {
        // Match ArtifactFunction's existing treatment of missing and middleman inputs.
        checkState(!(value instanceof RunfilesArtifactValue), "%s %s", input, value);
      }
    }
    for (RunfilesMetadataKey childKey : childKeys) {
      RunfilesMetadataValue child = (RunfilesMetadataValue) values.get(childKey);
      if (child == null) {
        return null;
      }
      children.add(child);
    }
    return new RunfilesMetadataValue(
        files.build(), fileValues.build(), trees.build(), treeValues.build(), children.build());
  }
}
