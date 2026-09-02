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
package com.google.devtools.build.lib.collect.nestedset;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.MutableGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.skyframe.ExecutionPhaseSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.HashSet;
import java.util.Set;

/** A graph-owned metadata snapshot for one branch of an artifact nested set. */
public final class RunfilesMetadataKey implements ExecutionPhaseSkyKey {
  private static final SkyKeyInterner<RunfilesMetadataKey> interner = SkyKey.newInterner();
  private final Object children;

  private RunfilesMetadataKey(Object children) {
    this.children = children;
  }

  public static RunfilesMetadataKey create(NestedSet<Artifact> inputs) {
    return createInternal(inputs.getChildren());
  }

  private static RunfilesMetadataKey createInternal(Object children) {
    return interner.intern(new RunfilesMetadataKey(children));
  }

  public ImmutableList<Artifact> directArtifacts() {
    if (children instanceof Artifact artifact) {
      return ImmutableList.of(artifact);
    }
    ImmutableList.Builder<Artifact> result = ImmutableList.builder();
    for (Object child : (Object[]) children) {
      if (child instanceof Artifact artifact) {
        result.add(artifact);
      }
    }
    return result.build();
  }

  public ImmutableList<RunfilesMetadataKey> childKeys() {
    if (children instanceof Artifact) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<RunfilesMetadataKey> result = ImmutableList.builder();
    for (Object child : (Object[]) children) {
      if (child instanceof Object[]) {
        result.add(createInternal(child));
      }
    }
    return result.build();
  }

  /** Add paths only to generated inputs whose producers were already selected for rewinding. */
  public boolean addGeneratedPathsToRewindGraph(MutableGraph<SkyKey> graph, SkyKey parent) {
    return addGeneratedPathsToRewindGraph(graph, parent, new HashSet<>());
  }

  private boolean addGeneratedPathsToRewindGraph(
      MutableGraph<SkyKey> graph, SkyKey parent, Set<RunfilesMetadataKey> seen) {
    if (graph.nodes().contains(this)) {
      graph.putEdge(parent, this);
      return true;
    }
    if (!seen.add(this)) {
      return false;
    }
    boolean hasGeneratedInputs = false;
    for (Artifact artifact : directArtifacts()) {
      if (!artifact.isSourceArtifact() && graph.nodes().contains(Artifact.key(artifact))) {
        graph.putEdge(this, Artifact.key(artifact));
        hasGeneratedInputs = true;
      }
    }
    for (RunfilesMetadataKey child : childKeys()) {
      hasGeneratedInputs |= child.addGeneratedPathsToRewindGraph(graph, this, seen);
    }
    if (hasGeneratedInputs) {
      graph.putEdge(parent, this);
    }
    return hasGeneratedInputs;
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.RUNFILES_METADATA;
  }

  @Override
  public boolean valueIsShareable() {
    return false;
  }

  @Override
  public SkyKeyInterner<?> getSkyKeyInterner() {
    return interner;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof RunfilesMetadataKey key && children == key.children;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(children);
  }

  @Override
  public String toString() {
    return "RunfilesMetadataKey@" + Integer.toHexString(hashCode());
  }
}
