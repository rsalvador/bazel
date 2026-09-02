// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteOutputChecker} */
@RunWith(JUnit4.class)
public class RemoteOutputCheckerTest {
  private static final TopLevelArtifactContext RUNFILES_CONTEXT = new TopLevelArtifactContext(
      false, false, false, ImmutableSortedSet.of(OutputGroupInfo.HIDDEN_TOP_LEVEL));
  private final RemoteOutputChecker remoteOutputChecker =
      new RemoteOutputChecker(
          new JavaClock(), "build", RemoteOutputsMode.MINIMAL, ImmutableList.of());
  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final ArtifactRoot execRoot =
      ArtifactRoot.asDerivedRoot(fs.getPath("/execroot"), ArtifactRoot.RootType.Output, "out");

  @Test
  public void testShouldDownloadOutput() {
    remoteOutputChecker.addOutputToDownload(
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/bar"));
    remoteOutputChecker.addOutputToDownload(
        ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz"));
    assertThat(remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-quz")))
        .isFalse();
    assertThat(remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar")))
        .isTrue();
    assertThat(
            remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar/data.txt")))
        .isTrue();
    assertThat(remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-baz")))
        .isTrue();
  }

  @Test
  public void sharedRunfilesAreRegisteredOnlyOnceAcrossTargets() {
    Artifact common1 = ActionsTestUtil.createArtifact(execRoot, "common1");
    Artifact common2 = ActionsTestUtil.createArtifact(execRoot, "common2");
    Artifact leaf1 = ActionsTestUtil.createArtifact(execRoot, "leaf1");
    Artifact leaf2 = ActionsTestUtil.createArtifact(execRoot, "leaf2");
    NestedSet<Artifact> shared = NestedSetBuilder.create(Order.STABLE_ORDER, common1, common2);
    CountingChecker checker = new CountingChecker();
    for (Artifact leaf : ImmutableList.of(leaf1, leaf2)) {
      Runfiles runfiles = new Runfiles.Builder("workspace")
          .addTransitiveArtifacts(shared).addArtifact(leaf).build();
      checker.afterTopLevelTargetAnalysis(target(runfiles), () -> RUNFILES_CONTEXT);
    }
    assertThat(checker.registrations).isEqualTo(4);
    for (Artifact artifact : ImmutableList.of(common1, common2, leaf1, leaf2)) {
      assertThat(checker.shouldDownloadOutput(artifact.getExecPath())).isTrue();
    }
    checker.skipDownload(common1.getExecPath());
    assertThat(checker.shouldDownloadOutput(common1.getExecPath())).isFalse();
    assertThat(new CountingChecker().shouldDownloadOutput(common2.getExecPath())).isFalse();
  }

  @Test
  public void runfilesTraversalIncludesSymlinksAndTrees() {
    Artifact symlinkTarget = ActionsTestUtil.createArtifact(execRoot, "symlink-target");
    Artifact rootSymlinkTarget = ActionsTestUtil.createArtifact(execRoot, "root-symlink-target");
    Artifact tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "tree");
    Runfiles runfiles = new Runfiles.Builder("workspace")
        .addArtifact(tree)
        .addSymlink(PathFragment.create("link"), symlinkTarget)
        .addRootSymlink(PathFragment.create("root-link"), rootSymlinkTarget)
        .build();
    CountingChecker checker = new CountingChecker();
    checker.afterTopLevelTargetAnalysis(target(runfiles), () -> RUNFILES_CONTEXT);

    assertThat(checker.shouldDownloadOutput(symlinkTarget.getExecPath())).isTrue();
    assertThat(checker.shouldDownloadOutput(rootSymlinkTarget.getExecPath())).isTrue();
    assertThat(checker.shouldDownloadOutput(tree.getExecPath().getRelative("child"))).isTrue();
    assertThat(checker.shouldDownloadOutput(PathFragment.create("out/unrelated"))).isFalse();
  }

  private ConfiguredTarget target(Runfiles runfiles) {
    RunfilesSupport support = mock(RunfilesSupport.class);
    when(support.getRunfiles()).thenReturn(runfiles);
    Artifact executable = ActionsTestUtil.createArtifact(execRoot, "executable");
    FilesToRunProvider provider = FilesToRunProvider.create(
        NestedSetBuilder.create(Order.STABLE_ORDER, executable), support, executable);
    ConfiguredTarget target = mock(ConfiguredTarget.class);
    when(target.getProvider(FilesToRunProvider.class)).thenReturn(provider);
    return target;
  }

  private static final class CountingChecker extends RemoteOutputChecker {
    private int registrations;

    CountingChecker() {
      super(new JavaClock(), "build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    }

    @Override
    public void addOutputToDownload(ActionInput input) {
      registrations++;
      super.addOutputToDownload(input);
    }
  }
}
