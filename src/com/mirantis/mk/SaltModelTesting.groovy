package com.mirantis.mk

/**
 * setup and test salt-master
 *
 * @param masterName          salt master's name
 * @param clusterName         model cluster name
 * @param extraFormulas       extraFormulas to install
 * @param formulasSource      formulas source (git or pkg)
 * @param testDir             directory of model
 * @param formulasSource      Salt formulas source type (optional, default pkg)
 * @param formulasRevision    APT revision for formulas (optional default stable)
 * @param ignoreClassNotfound Ignore missing classes for reclass model
 * @param dockerMaxCpus       max cpus passed to docker (default 0, disabled)
 * @param legacyTestingMode   do you want to enable legacy testing mode (iterating through the nodes directory definitions instead of reading cluster models)
 */

def setupAndTestNode(masterName, clusterName, extraFormulas, testDir, formulasSource = 'pkg', formulasRevision = 'stable', dockerMaxCpus = 0, ignoreClassNotfound = false, legacyTestingMode = false) {

  def saltOpts = "--retcode-passthrough --force-color"
  def common = new com.mirantis.mk.Common()
  def workspace = common.getWorkspace()
  def imageFound = true
  def img
  try {
    img = docker.image("tcpcloud/salt-models-testing")
    img.pull()
  } catch (Throwable e) {
    img = docker.image("ubuntu:latest")
    imageFound = false
  }

  if (!extraFormulas || extraFormulas == "") {
    extraFormulas = "linux"
  }

  def dockerMaxCpusOption = ""
  if (dockerMaxCpus > 0) {
    dockerMaxCpusOption = "--cpus=${dockerMaxCpus}"
  }

  img.inside("-u root:root --hostname=${masterName} --ulimit nofile=4096:8192 ${dockerMaxCpusOption}") {
    if (!imageFound) {
      sh("apt-get update && apt-get install -y curl git python-pip sudo python-pip python-dev zlib1g-dev git")
      sh("pip install git+https://github.com/salt-formulas/reclass.git --upgrade")
    }
    sh("mkdir -p /srv/salt/scripts/ || true")
    sh("cp -r ${testDir} /srv/salt/reclass")
    sh("git config --global user.email || git config --global user.email 'ci@ci.local'")
    sh("git config --global user.name || git config --global user.name 'CI'")
    sh("git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts")

    withEnv(["FORMULAS_SOURCE=${formulasSource}", "EXTRA_FORMULAS=${extraFormulas}", "DISTRIB_REVISION=${formulasRevision}", "DEBUG=1", "MASTER_HOSTNAME=${masterName}", "CLUSTER_NAME=${clusterName}", "MINION_ID=${masterName}", "HOSTNAME=cfg01", "DOMAIN=mk-ci.local", "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}" ]){
        sh("bash -c 'echo $MASTER_HOSTNAME'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && system_config_master'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && saltmaster_bootstrap'")
        sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && source_local_envs && saltmaster_init'")

        if (!legacyTestingMode) {
           sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minions'")
        }
    }

    if (legacyTestingMode) {
      common.infoMsg("Running legacy mode test for master hostname ${masterName}")
      def nodes = sh script: "find /srv/salt/reclass/nodes -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true
      for (minion in nodes.tokenize()) {
        def basename = sh script: "basename ${minion} .yml", returnStdout: true
        if (!basename.trim().contains(masterName)) {
          testMinion(basename.trim())
        }
      }
    }
  }
}

/**
 * Test salt-minion
 *
 * @param minion          salt minion
 */

def testMinion(minionName)
{
  sh("bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'")
}
