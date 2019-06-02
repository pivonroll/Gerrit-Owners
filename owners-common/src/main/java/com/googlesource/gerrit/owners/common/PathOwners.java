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

import static com.google.gerrit.reviewdb.client.Patch.COMMIT_MSG;
import static com.google.gerrit.reviewdb.client.Patch.MERGE_LIST;
import static com.googlesource.gerrit.owners.common.JgitWrapper.getBlobAsBytes;

import com.google.common.collect.*;
import com.google.gerrit.extensions.client.DiffPreferencesInfo;
import com.google.gerrit.reviewdb.client.*;
import com.google.gerrit.reviewdb.client.Account.Id;
import com.google.gerrit.server.patch.*;

import java.io.IOException;
import java.util.*;

import com.google.gerrit.server.query.change.ChangeData;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Calculates the owners of a patch list. */
// TODO(vspivak): provide assisted factory
public class PathOwners {

  private static final Logger log = LoggerFactory.getLogger(PathOwners.class);

  private final SetMultimap<String, Account.Id> owners;

  private final Repository repository;

  private final PatchList patchList;

  private final ConfigurationParser parser;

  private final Accounts accounts;

  private final ChangeData changeData;

  private PatchListCache patchListCache;

  private Map<String, Matcher> matchers;

  private Map<String, Set<Id>> fileOwners;

  public PathOwners(Accounts accounts,
                    Repository repository,
                    String branch,
                    PatchList patchList,
                    ChangeData changeData,
                    PatchListCache patchListCache) {
    this.repository = repository;
    this.patchList = patchList;
    this.parser = new ConfigurationParser(accounts);
    this.accounts = accounts;
    this.changeData = changeData;
    this.patchListCache = patchListCache;

    OwnersMap map = fetchOwners(branch);
    owners = Multimaps.unmodifiableSetMultimap(map.getPathOwners());
    matchers = map.getMatchers();
    fileOwners = map.getFileOwners();
  }

  /**
   * Returns a read only view of the paths to owners mapping.
   *
   * @return multimap of paths to owners
   */
  public SetMultimap<String, Account.Id> get() {
    return owners;
  }

  public Map<String, Matcher> getMatchers() {
    return matchers;
  }

  public Map<String, Set<Account.Id>> getFileOwners() {
    return fileOwners;
  }

  /**
   * Fetched the owners for the associated patch list.
   *
   * @return A structure containing matchers paths to owners
   */
  private OwnersMap fetchOwners(String branch) {
    OwnersMap ownersMap = new OwnersMap();
    try {
      String rootPath = "OWNERS";

      PathOwnersEntry projectEntry =
          getOwnersConfig(rootPath, RefNames.REFS_CONFIG)
              .map(conf -> new PathOwnersEntry(rootPath, conf, accounts, Collections.emptySet()))
              .orElse(new PathOwnersEntry());

      PathOwnersEntry rootEntry =
          getOwnersConfig(rootPath, branch)
              .map(conf -> new PathOwnersEntry(rootPath, conf, accounts, Collections.emptySet()))
              .orElse(new PathOwnersEntry());

      Set<String> modifiedPaths = getModifiedPaths();
      Map<String, PathOwnersEntry> entries = new HashMap<>();
      PathOwnersEntry currentEntry = null;
      for (String path : modifiedPaths) {
        currentEntry = resolvePathEntry(path, branch, projectEntry, rootEntry, entries);

        // add owners to file for matcher predicates
        ownersMap.addFileOwners(path, currentEntry.getOwners());

        // Only add the path to the OWNERS file to reduce the number of
        // entries in the result
        if (currentEntry.getOwnersPath() != null) {
          ownersMap.addPathOwners(currentEntry.getOwnersPath(), currentEntry.getOwners());
        }
        ownersMap.addMatchers(currentEntry.getMatchers());
      }

      // We need to only keep matchers that match files in the patchset
      Map<String, Matcher> matchers = ownersMap.getMatchers();
      if (matchers.size() > 0) {
        HashMap<String, Matcher> newMatchers = Maps.newHashMap();
        // extra loop
        for (String path : modifiedPaths) {
          if (!hasPathChangedAfterLastApproval(path, ownersMap.getMatchers())) {
            continue;
          }
          processMatcherPerPath(matchers, newMatchers, path, ownersMap);
        }
        if (matchers.size() != newMatchers.size()) {
          ownersMap.setMatchers(newMatchers);
        }
      }
      return ownersMap;
    } catch (IOException e) {
      log.warn("Invalid OWNERS file", e);
      return ownersMap;
    }
  }

  private void processMatcherPerPath(
      Map<String, Matcher> fullMatchers,
      HashMap<String, Matcher> newMatchers,
      String path,
      OwnersMap ownersMap) {
    Iterator<Matcher> it = fullMatchers.values().iterator();
    while (it.hasNext()) {
      Matcher matcher = it.next();
      if (matcher.matches(path)) {
        newMatchers.put(matcher.getPath(), matcher);
        ownersMap.addFileOwners(path, matcher.getOwners());
      }
    }
  }

  private PathOwnersEntry resolvePathEntry(
      String path,
      String branch,
      PathOwnersEntry projectEntry,
      PathOwnersEntry rootEntry,
      Map<String, PathOwnersEntry> entries)
      throws IOException {
    String[] parts = path.split("/");
    PathOwnersEntry currentEntry = rootEntry;
    StringBuilder builder = new StringBuilder();

    if (rootEntry.isInherited()) {
      for (Matcher matcher : projectEntry.getMatchers().values()) {
        if (!currentEntry.hasMatcher(matcher.getPath())) {
          currentEntry.addMatcher(matcher);
        }
      }
      if (currentEntry.getOwners().isEmpty()) {
        currentEntry.setOwners(projectEntry.getOwners());
      }
      if (currentEntry.getOwnersPath() == null) {
        currentEntry.setOwnersPath(projectEntry.getOwnersPath());
      }
    }

    // Iterate through the parent paths, not including the file name
    // itself
    for (int i = 0; i < parts.length - 1; i++) {
      String part = parts[i];
      builder.append(part).append("/");
      String partial = builder.toString();

      // Skip if we already parsed this path
      if (entries.containsKey(partial)) {
        currentEntry = entries.get(partial);
      } else {
        String ownersPath = partial + "OWNERS";
        Optional<OwnersConfig> conf = getOwnersConfig(ownersPath, branch);
        final Set<Id> owners = currentEntry.getOwners();
        currentEntry =
            conf.map(c -> new PathOwnersEntry(ownersPath, c, accounts, owners))
                .orElse(currentEntry);
        if (conf.map(OwnersConfig::isInherited).orElse(false)) {
          for (Matcher m : currentEntry.getMatchers().values()) {
            currentEntry.addMatcher(m);
          }
        }
        entries.put(partial, currentEntry);
      }
    }
    return currentEntry;
  }


  private Optional<DiffSummary> getDiffSummaryBetweenPatchSets(PatchSet patchSetBase, PatchSet patchSetTarget) {
    Optional<DiffSummary> diffSummary;

    ObjectId patchSetBaseCommitId = null;
    if (patchSetBase != null) {
      patchSetBaseCommitId = ObjectId.fromString(patchSetBase.getRevision().get());
    }

    ObjectId patchSetTargetCommitId = ObjectId.fromString(patchSetTarget.getRevision().get());

    PatchListKey patchListKey = PatchListKey.againstCommit(patchSetBaseCommitId, patchSetTargetCommitId,
            DiffPreferencesInfo.Whitespace.IGNORE_NONE);

    DiffSummaryKey key = DiffSummaryKey.fromPatchListKey(patchListKey);
    try {
      diffSummary = Optional.of(patchListCache.getDiffSummary(key, changeData.project()));
    } catch (PatchListNotAvailableException e) {
      diffSummary = Optional.empty();
    }

    return diffSummary;
  }

  private Optional<Matcher> findPathMatcher(String path, Map<String, Matcher> pathOwners) {
    Iterator<Matcher> it = pathOwners.values().iterator();
    while (it.hasNext()) {
      Matcher matcher = it.next();
      if (matcher.matches(path)) {
        return Optional.of(matcher);
      }
    }

    return Optional.empty();
  }

  private boolean hasFileOwnerApprovedPatchSet(String path, PatchSet patchSet, Map<String, Matcher> pathOwners) {
    if (pathOwners.isEmpty()) {
      return false;
    }

    Optional<Matcher> matcher = findPathMatcher(path, pathOwners);

    if (!matcher.isPresent()) {
      return false;
    }
    for (Id fileOwner : matcher.get().getOwners()) {
      for (PatchSetApproval approval : changeData.approvals().get(patchSet.getId())) {
        if (approval.getAccountId().equals(fileOwner) && approval.getValue() == 2) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasPathChangedInBetweenPatchSets(String path, PatchSet basePatchSet, PatchSet currentPatchSet) {
    if (changeData == null) {
      return false;
    }

    Optional<DiffSummary> diffSummary = getDiffSummaryBetweenPatchSets(basePatchSet, currentPatchSet);
    if (!diffSummary.isPresent()) {
      return false;
    }
    List<String> modifiedPaths = diffSummary.get().getPaths();
    return modifiedPaths.contains(path);
  }

  /**
   * if path was never approved it returns true
   * if path was approved but never changed it's diff with base after that, it returns false
   * else if path was approved and has changed since last approval it returns true
   */
  private boolean hasPathChangedAfterLastApproval(String path, Map<String, Matcher> pathOwners) {
    ArrayList<PatchSet> patchSets = new ArrayList<>(changeData.patchSets());

    if (patchSets.isEmpty() || patchSets.size() <= 1) {
      return true;
    }

    // go through every patch-set and check if file was approved and not modified afterwards
    PatchSet pathPatchSet = null;
    Map<String, PatchSet.Id> pathApprovals = new HashMap<>();
    for(PatchSet currentPatchSet : patchSets) {
      // check if path has changed between currentPatchSet set and pathPatchSet
      // and check if patch-set was approved by file owner is approved in patch-set
      // if yes store into map<path, PatchSet.id>
      if (hasPathChangedInBetweenPatchSets(path, pathPatchSet, currentPatchSet)) {
        if (hasFileOwnerApprovedPatchSet(path, currentPatchSet, pathOwners)) {
          pathPatchSet = currentPatchSet;
          pathApprovals.put(path, pathPatchSet.getId());
        } else {
          pathApprovals.remove(path);
        }
      }
    }

    // if path set associated with path in map is equal to the last patch set (by id) then path is not approved
    // otherwise path is approved
    if (pathApprovals.isEmpty()) {
      return true;
    } else if (pathApprovals.containsKey(path)) {
      return pathApprovals.get(path).equals(changeData.currentPatchSet().getId());
    }
    return true;
  }

  /**
   * Parses the patch list for any paths that were modified.
   *
   * @return set of modified paths.
   */
  private Set<String> getModifiedPaths() {
    Set<String> paths = Sets.newHashSet();

    for (PatchListEntry patch : patchList.getPatches()) {
      // Ignore commit message and Merge List
      String newName = patch.getNewName();
      if (!COMMIT_MSG.equals(newName) && !MERGE_LIST.equals(newName)) {
        paths.add(newName);

        // If a file was moved then we need approvals for old and new
        // path
        if (patch.getChangeType() == Patch.ChangeType.RENAMED) {
          paths.add(patch.getOldName());
        }
      }
    }
    return paths;
  }

  /**
   * Returns the parsed FileOwnersConfig file for the given path if it exists.
   *
   * @param ownersPath path to OWNERS file in the git repo
   * @return config or null if it doesn't exist
   * @throws IOException
   */
  private Optional<OwnersConfig> getOwnersConfig(String ownersPath, String branch)
      throws IOException {
    return getBlobAsBytes(repository, branch, ownersPath)
        .flatMap(bytes -> parser.getOwnersConfig(bytes));
  }
}
