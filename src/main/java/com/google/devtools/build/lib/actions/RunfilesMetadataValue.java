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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable metadata chunks whose sharing and invalidation are owned by Skyframe. */
public final class RunfilesMetadataValue implements SkyValue {
  private final ImmutableList<Artifact> files;
  private final ImmutableList<FileArtifactValue> fileValues;
  private final ImmutableList<Artifact> trees;
  private final ImmutableList<TreeArtifactValue> treeValues;
  private final ImmutableList<RunfilesMetadataValue> children;
  private final boolean hasTrees;

  public RunfilesMetadataValue(
      ImmutableList<Artifact> files,
      ImmutableList<FileArtifactValue> fileValues,
      ImmutableList<Artifact> trees,
      ImmutableList<TreeArtifactValue> treeValues,
      ImmutableList<RunfilesMetadataValue> children) {
    checkArgument(files.size() == fileValues.size());
    checkArgument(trees.size() == treeValues.size());
    this.files = files;
    this.fileValues = fileValues;
    this.trees = trees;
    this.treeValues = treeValues;
    this.children = children;
    this.hasTrees = !trees.isEmpty() || children.stream().anyMatch(RunfilesMetadataValue::hasTrees);
  }

  public boolean hasTrees() {
    return hasTrees;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RunfilesMetadataValue value)
        || !files.equals(value.files) || !fileValues.equals(value.fileValues)
        || !trees.equals(value.trees) || !treeValues.equals(value.treeValues)
        || children.size() != value.children.size()) {
      return false;
    }
    // Child snapshots belong to Skyframe. Compare their identities, not every path through
    // the shared graph. This is conservative for change pruning, never for correctness:
    // RunfilesArtifactValue still compares the complete contents at the consumer boundary.
    for (int i = 0; i < children.size(); i++) {
      if (children.get(i) != value.children.get(i)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(files, fileValues, trees, treeValues);
    for (RunfilesMetadataValue child : children) {
      result = 31 * result + System.identityHashCode(child);
    }
    return result;
  }

  public void collect(
      Map<Artifact, FileArtifactValue> filesOut,
      Map<Artifact, TreeArtifactValue> treesOut) {
    collect(filesOut, treesOut, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private void collect(
      Map<Artifact, FileArtifactValue> filesOut,
      Map<Artifact, TreeArtifactValue> treesOut,
      Set<RunfilesMetadataValue> seen) {
    if (!seen.add(this)) {
      return;
    }
    for (RunfilesMetadataValue child : children) {
      child.collect(filesOut, treesOut, seen);
    }
    for (int i = 0; i < files.size(); i++) {
      FileArtifactValue previous = filesOut.put(files.get(i), fileValues.get(i));
      checkState(previous == null || previous.equals(fileValues.get(i)),
          "Inconsistent runfiles metadata for %s", files.get(i));
    }
    for (int i = 0; i < trees.size(); i++) {
      TreeArtifactValue previous = treesOut.put(trees.get(i), treeValues.get(i));
      checkState(previous == null || previous.equals(treeValues.get(i)),
          "Inconsistent tree metadata for %s", trees.get(i));
    }
  }
}
