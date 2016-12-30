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
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.vmware.gerrit.owners.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.powermock.api.easymock.PowerMock.replayAll;
import static com.vmware.gerrit.owners.common.StreamUtils.iteratorStream;

import com.google.gerrit.reviewdb.client.Account;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(PowerMockRunner.class)
@PrepareForTest(JgitWrapper.class)
public class RegexTest extends RegexConfig {

  @Override
  @Before
  public void setup() throws Exception {
    super.setup();
  }

  @Test
  public void testNewParsingYaml() throws Exception {
    replayAll();
    String yamlString = ADVANCED_CONFIG_FULL;
    // the function to test
    Optional<OwnersConfig> configNullable = getOwnersConfig(yamlString);
    // check classical configuration
    assertTrue(configNullable.isPresent());
    OwnersConfig config = configNullable.get();
    Set<String> owners = config.getOwners();
    assertEquals(2, owners.size());
    assertTrue(owners.contains("jane@email.com"));
    assertTrue(owners.contains("john@email.com"));
    // check matchers
    Map<String, Matcher> matchers = config.getMatchers();
    assertEquals(2, matchers.size());
    assertTrue(matchers.containsKey(".sql"));
    assertTrue(matchers.containsKey("Product.scala"));
    Matcher advMatcher = matchers.get("Product.scala");
    assertEquals(2, advMatcher.getOwners().size());
    Set<Account.Id> advOwners = advMatcher.getOwners();
    assertTrue(advOwners.contains(accounts.get("bob@email.com")));
    assertTrue(advOwners.contains(accounts.get("alice@email.com")));
    Matcher dbMatcher = matchers.get(".sql");
    assertEquals(2, dbMatcher.getOwners().size());
    Set<Account.Id> dbOwners = dbMatcher.getOwners();
    assertTrue(dbOwners.contains(accounts.get("philip@email.com")));
    assertTrue(dbOwners.contains(accounts.get("frank@email.com")));
  }

  @Test
  public void checkMatchers() throws Exception {
    expectWrapper("OWNERS",
        Optional.of(ADVANCED_PARENT_CONFIG.getBytes()));
    expectWrapper("project/OWNERS",
        Optional.of(ADVANCED_CONFIG.getBytes()));
    creatingPatchList(Arrays.asList("file1.txt", "project/afile2.txt",
        "project/bfile.txt"));
    replayAll();
    // function under test
    PathOwners owners =
        new PathOwners(resolver, db, repository, patchList);
    // assertions on classic owners
    Set<Account.Id> ownersSet = owners.get().get("project/OWNERS");
    assertEquals(2, ownersSet.size());
    // get matches
    Map<String, Matcher> matches = owners.getMatches();
    // asserts we have 1 exact matcher
    List<Entry<String, Matcher>> onlyExacts =
        iteratorStream(matches.entrySet().iterator())
            .filter(entry -> entry.getValue() instanceof ExactMatcher)
            .collect(Collectors.toList());
    assertEquals(1, onlyExacts.size());
    assertEquals("file1.txt", onlyExacts.get(0).getKey());
    // ... 1 regex matcher
    List<Entry<String, Matcher>> regexList =
        StreamUtils.iteratorStream(matches.entrySet().iterator())
            .filter(entry -> entry.getValue() instanceof RegExMatcher)
            .collect(Collectors.toList());
    assertEquals(1, regexList.size());
    assertEquals(".*/a.*", regexList.get(0).getKey());
    // ... and not other matchers
    assertEquals(2, matches.size());
    matches.forEach((key, value) -> {
      final StringBuffer buf = new StringBuffer("key:" + key + "\n"
          + "path:" + value.getPath() + "\n" + "owners: ");
      value.getOwners().forEach(account -> {
        buf.append(account.toString() + " ");
      });
      buf.append("\n");
      System.out.println(buf);
    });
  }
}
