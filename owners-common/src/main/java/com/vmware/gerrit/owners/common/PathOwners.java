/*
 * Copyright (c) 2013 VMware, Inc. All Rights Reserved.
 */
package com.vmware.gerrit.owners.common;

import static com.vmware.gerrit.owners.common.JgitWrapper.getBlobAsBytes;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Account.Id;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListEntry;

import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Calculates the owners of a patch list.
 */
// TODO(vspivak): provide assisted factory
public class PathOwners {

  private static final Logger log = LoggerFactory.getLogger(PathOwners.class);

  private final SetMultimap<String, Account.Id> owners;

  private final Repository repository;

  private final PatchList patchList;

  private final ConfigurationParser parser;

  private Map<String, Matcher> matches;

  public PathOwners(AccountResolver resolver, ReviewDb db,
      Repository repository, PatchList patchList) {
    this.repository = repository;
    this.patchList = patchList;
    this.parser = new ConfigurationParser(resolver, db);

    OwnersMap map = fetchOwners();
    owners = Multimaps.unmodifiableSetMultimap(map.getPathOwners());
    matches = map.getMatchers();
  }

  /**
   * Returns a read only view of the paths to owners mapping.
   *
   * @return multimap of paths to owners
   */
  public SetMultimap<String, Account.Id> get() {
    return owners;
  }

  public Map<String, Matcher> getMatches() {
    return matches;
  }

  /**
   * Fetched the owners for the associated patch list.
   *
   * @return A structure containing matchers paths to owners
   */
  private OwnersMap fetchOwners() {
    OwnersMap ownersMap = new OwnersMap();
    try {
      String rootPath = "OWNERS";
      PathOwnersEntry rootEntry =
          getOwnersConfig(rootPath).map(
              conf -> new PathOwnersEntry(rootPath, conf, parser, Collections
                  .emptySet())).orElse(new PathOwnersEntry());

      Set<String> modifiedPaths = getModifiedPaths();
      Map<String, PathOwnersEntry> entries = new HashMap<>();
      for (String path : modifiedPaths) {
        PathOwnersEntry currentEntry = resolvePathEntry(path, rootEntry, entries);

        // Only add the path to the OWNERS file to reduce the number of
        // entries in the result
        if (currentEntry.getOwnersPath() != null) {
          ownersMap.addPathOwners(currentEntry.getOwnersPath(),
              currentEntry.getOwners());
        }
        ownersMap.addMatchers(currentEntry.getMatchers());
      }

      // We need to only keep matchers that match files in the patchset
      Map<String, Matcher> fullMatchers = ownersMap.getMatchers();
      if (fullMatchers.size() > 0) {
        HashMap<String, Matcher> newMatchers = Maps.newHashMap();
        // extra loop
        for (String path : modifiedPaths) {
          processMatcherPerPath(fullMatchers, newMatchers, path);
        }
        if (fullMatchers.size() != newMatchers.size()) {
          ownersMap.setMatchers(newMatchers);
        }
      }
      return ownersMap;
    } catch (IOException e) {
      log.warn("Invalid OWNERS file", e);
      return ownersMap;
    }
  }

  private void processMatcherPerPath(Map<String, Matcher> fullMatchers,
      HashMap<String, Matcher> newMatchers, String path) {
    Iterator<Matcher> it = fullMatchers.values().iterator();
    while (it.hasNext()) {
      Matcher matcher = it.next();
      if (matcher.matches(path)) {
        newMatchers.put(matcher.getPath(), matcher);
      }
    }
  }

  private PathOwnersEntry resolvePathEntry(String path,
      PathOwnersEntry rootEntry, Map<String, PathOwnersEntry> entries) throws IOException {
    String[] parts = path.split("/");
    PathOwnersEntry currentEntry = rootEntry;
    Set<Id> currentOwners = currentEntry.getOwners();
    StringBuilder builder = new StringBuilder();
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
        currentEntry =
            getOwnersConfig(ownersPath).map(
                conf -> new PathOwnersEntry(ownersPath, conf, parser,
                    currentOwners)).orElse(currentEntry);
        entries.put(partial, currentEntry);
      }
    }
    return currentEntry;
  }

  /**
   * Parses the patch list for any paths that were modified.
   *
   * @return set of modified paths.
   */
  private Set<String> getModifiedPaths() {
    Set<String> paths = Sets.newHashSet();
    for (PatchListEntry patch : patchList.getPatches()) {
      // Ignore commit message
      if (!patch.getNewName().equals("/COMMIT_MSG")) {
        paths.add(patch.getNewName());

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
  private Optional<OwnersConfig> getOwnersConfig(String ownersPath)
      throws IOException {
    return getBlobAsBytes(repository, "master", ownersPath).flatMap(
        bytes -> parser.getOwnersConfig(bytes));
  }
}
