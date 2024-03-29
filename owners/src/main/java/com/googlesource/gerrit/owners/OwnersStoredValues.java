// Copyright (c) 2013 VMware, Inc. All Rights Reserved.
// Copyright (C) 2017 The Android Open Source Project
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

package com.googlesource.gerrit.owners;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.rules.StoredValue;
import com.google.gerrit.server.rules.StoredValues;
import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlesource.gerrit.owners.common.Accounts;
import com.googlesource.gerrit.owners.common.PathOwners;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** StoredValues for the Gerrit OWNERS plugin. */
public class OwnersStoredValues {
  private static final Logger log = LoggerFactory.getLogger(OwnersStoredValues.class);

  public static StoredValue<PathOwners> PATH_OWNERS;

  public static synchronized void initialize(Accounts accounts, ChangeData.Factory changeDataFactory, PatchListCache patchListCache) {
    if (PATH_OWNERS != null) {
      return;
    }
    log.info("Initializing OwnerStoredValues");
    PATH_OWNERS =
        new StoredValue<PathOwners>() {
          @Override
          protected PathOwners createValue(Prolog engine) {
            Change.Id changeId = StoredValues.getChange(engine).getId();
            Project.NameKey projectNameKey = StoredValues.getChange(engine).getProject();
            ChangeData changeData = changeDataFactory.create(projectNameKey, changeId);
            PatchList patchList = StoredValues.PATCH_LIST.get(engine);
            Repository repository = StoredValues.REPOSITORY.get(engine);
//          String branch = StoredValues.getChange(engine).getDest().get();
            String branch = StoredValues.getChange(engine).getDest().branch();
            return new PathOwners(accounts, repository, branch, patchList, changeData, patchListCache);
          }
        };
  }

  private OwnersStoredValues() {}
}
