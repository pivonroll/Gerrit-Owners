// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
// implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.vmware.gerrit.owners.common;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.inject.Inject;
import com.googlesource.gerrit.owners.common.Accounts;
import com.googlesource.gerrit.owners.common.GitRefListener;
import com.googlesource.gerrit.owners.common.ReviewerManager;
import org.eclipse.jgit.lib.Repository;
import org.junit.Ignore;

@Ignore
public class GitRefListenerTest extends GitRefListener {
  int processedEvents = 0;

  @Inject
  public GitRefListenerTest(
          GerritApi api,
          PatchListCache patchListCache,
          GitRepositoryManager repositoryManager,
          Accounts accounts,
          ReviewerManager reviewerManager) {
    super(api, patchListCache, repositoryManager, accounts, reviewerManager);
  }

  @Override
  public void processEvent(Repository repository, Event event) {
    processedEvents++;
  }

  int getProcessedEvents() {
    return processedEvents;
  }
}
