/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.cluster;

import java.io.IOException;
import java.net.URL;

import org.apache.curator.test.TestingServer;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.helix.HelixException;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.manager.zk.ZkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import org.apache.gobblin.testing.AssertWithBackoff;


/**
 * Unit tests for {@link GobblinTaskRunner}.
 *
 * <p>
 *   This class uses a {@link TestingServer} as an embedded ZooKeeper server for testing. A
 *   {@link GobblinClusterManager} instance is used to send the test shutdown request message.
 * </p>
 *
 * @author Yinan Li
 */
@Test(groups = { "gobblin.cluster" })
public class GobblinTaskRunnerTest {
  public final static Logger LOG = LoggerFactory.getLogger(GobblinTaskRunnerTest.class);

  public static final String HADOOP_OVERRIDE_PROPERTY_NAME = "prop";

  private TestingServer testingZKServer;

  private GobblinTaskRunner gobblinTaskRunner;

  private GobblinClusterManager gobblinClusterManager;
  private GobblinTaskRunner corruptGobblinTaskRunner;
  private String clusterName;
  private String corruptHelixInstance;

  @BeforeClass
  public void setUp() throws Exception {
    this.testingZKServer = new TestingServer(-1);
    LOG.info("Testing ZK Server listening on: " + testingZKServer.getConnectString());

    URL url = GobblinTaskRunnerTest.class.getClassLoader().getResource(
        GobblinTaskRunnerTest.class.getSimpleName() + ".conf");
    Assert.assertNotNull(url, "Could not find resource " + url);

    Config config = ConfigFactory.parseURL(url)
        .withValue("gobblin.cluster.zk.connection.string",
                   ConfigValueFactory.fromAnyRef(testingZKServer.getConnectString()))
        .withValue(GobblinClusterConfigurationKeys.HADOOP_CONFIG_OVERRIDES_PREFIX + "." + HADOOP_OVERRIDE_PROPERTY_NAME,
            ConfigValueFactory.fromAnyRef("value"))
        .withValue(GobblinClusterConfigurationKeys.HADOOP_CONFIG_OVERRIDES_PREFIX + "." + "fs.file.impl.disable.cache",
            ConfigValueFactory.fromAnyRef("true"))
        .resolve();

    String zkConnectionString = config.getString(GobblinClusterConfigurationKeys.ZK_CONNECTION_STRING_KEY);
    this.clusterName = config.getString(GobblinClusterConfigurationKeys.HELIX_CLUSTER_NAME_KEY);
    HelixUtils.createGobblinHelixCluster(zkConnectionString, this.clusterName);

    // Participant
    this.gobblinTaskRunner =
        new GobblinTaskRunner(TestHelper.TEST_APPLICATION_NAME, TestHelper.TEST_HELIX_INSTANCE_NAME,
            TestHelper.TEST_APPLICATION_ID, TestHelper.TEST_TASK_RUNNER_ID, config, Optional.<Path>absent());
    this.gobblinTaskRunner.connectHelixManager();

    // Participant with a partial Instance set up on Helix/ZK
    this.corruptHelixInstance = HelixUtils.getHelixInstanceName("CorruptHelixInstance", 0);
    this.corruptGobblinTaskRunner =
        new GobblinTaskRunner(TestHelper.TEST_APPLICATION_NAME, corruptHelixInstance,
            TestHelper.TEST_APPLICATION_ID, TestHelper.TEST_TASK_RUNNER_ID, config, Optional.<Path>absent());

    // Controller
    this.gobblinClusterManager =
        new GobblinClusterManager(TestHelper.TEST_APPLICATION_NAME, TestHelper.TEST_APPLICATION_ID, config,
            Optional.<Path>absent());
    this.gobblinClusterManager.connectHelixManager();
  }

  @Test
  public void testSendReceiveShutdownMessage() throws Exception {
    Logger log = LoggerFactory.getLogger("testSendReceiveShutdownMessage");
    this.gobblinClusterManager.sendShutdownRequest();

    // Give Helix some time to handle the message
    AssertWithBackoff.create().logger(log).timeoutMs(20000)
      .assertTrue(new Predicate<Void>() {
        @Override public boolean apply(Void input) {
          return GobblinTaskRunnerTest.this.gobblinTaskRunner.isStopped();
        }
      }, "gobblinTaskRunner stopped");
  }

  @Test
  public void testBuildFileSystemConfig() {
    FileSystem fileSystem = this.gobblinTaskRunner.getFs();
    Assert.assertEquals(fileSystem.getConf().get(HADOOP_OVERRIDE_PROPERTY_NAME), "value");
  }

  @Test
  public void testConnectHelixManagerWithRetry() {
    //Connect and disconnect the corrupt task runner to create a Helix Instance set up.
    try {
      this.corruptGobblinTaskRunner.connectHelixManager();
      this.corruptGobblinTaskRunner.disconnectHelixManager();
    } catch (Exception e) {
      Assert.fail("Failed to connect to ZK");
    }

    //Delete ERRORS/HISTORY/STATUSUPDATES znodes under INSTANCES to simulate partial instance set up.
    ZkClient zkClient = new ZkClient(testingZKServer.getConnectString());
    zkClient.delete(PropertyPathBuilder.instanceError(clusterName, corruptHelixInstance));
    zkClient.delete(PropertyPathBuilder.instanceHistory(clusterName, corruptHelixInstance));
    zkClient.delete(PropertyPathBuilder.instanceStatusUpdate(clusterName, corruptHelixInstance));

    //Ensure that the connecting to Helix without retry will throw a HelixException
    try {
      corruptGobblinTaskRunner.connectHelixManager();
      Assert.fail("Unexpected success in connecting to HelixManager");
    } catch (Exception e) {
      //Assert that a HelixException is thrown.
      Assert.assertTrue(e.getClass().equals(HelixException.class));
    }

    //Ensure that connect with retry succeeds
    corruptGobblinTaskRunner.connectHelixManagerWithRetry();
    Assert.assertTrue(true);
  }

  @AfterClass
  public void tearDown() throws IOException {
    try {
      this.gobblinClusterManager.disconnectHelixManager();
      this.gobblinTaskRunner.disconnectHelixManager();
    } finally {
      this.testingZKServer.close();
    }
  }
}
