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

package com.googlesource.gerrit.owners.common;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ReviewerManager {
  private static final Logger log = LoggerFactory.getLogger(ReviewerManager.class);

  private final OneOffRequestContext requestContext;
  private final GerritApi gApi;

  @Inject
  public ReviewerManager(OneOffRequestContext requestContext, GerritApi gApi) {
    this.requestContext = requestContext;
    this.gApi = gApi;
  }

  public void addReviewers(ChangeApi cApi, Collection<Account.Id> reviewers)
      throws ReviewerManagerException {
    try {
      ChangeInfo changeInfo = cApi.get();
      try (ManualRequestContext ctx =
          requestContext.openAs(new Account.Id(changeInfo.owner._accountId))) {
        // TODO(davido): Switch back to using changes API again,
        // when it supports batch mode for adding reviewers
        ReviewInput in = new ReviewInput();
        in.reviewers = new ArrayList<>(reviewers.size());
        for (Account.Id account : reviewers) {
          AddReviewerInput addReviewerInput = new AddReviewerInput();
          addReviewerInput.reviewer = account.toString();
          in.reviewers.add(addReviewerInput);
        }
        gApi.changes().id(changeInfo.id).current().review(in);
      }
    } catch (RestApiException e) {
      log.error("Couldn't add reviewers to the change", e);
      throw new ReviewerManagerException(e);
    }
  }
}
