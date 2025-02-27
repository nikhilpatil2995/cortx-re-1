#!/usr/bin/env groovy
// CLEANUP REQUIRED
pipeline { 
    agent {
        node {
            label "docker-${OS_VERSION}-node"
        }
    }

    options { 
        skipDefaultCheckout()
        timeout(time: 180, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm')  
    }

    parameters {  
	    string(name: 'SSPL_URL', defaultValue: 'https://github.com/Seagate/cortx-monitor', description: 'Repo for SSPL')
        string(name: 'SSPL_BRANCH', defaultValue: 'main', description: 'Branch for SSPL') 
        choice(name: 'DEBUG', choices: ["no", "yes" ], description: 'Keep Host for Debuging')
        string(name: 'HOST', defaultValue: '-', description: 'Host FQDN',  trim: true)
        password(name: 'HOST_PASS', defaultValue: '-', description: 'Host machine root user password')    
	}

    environment {

        // S3Server Repo Info

        GPR_REPO = "https://github.com/${ghprbGhRepository}"
        SSPL_URL = "${ghprbGhRepository != null ? GPR_REPO : SSPL_URL}"
        SSPL_BRANCH = "${sha1 != null ? sha1 : SSPL_BRANCH}"

        SSPL_GPR_REFSEPEC = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
        SSPL_BRANCH_REFSEPEC = "+refs/heads/*:refs/remotes/origin/*"
        SSPL_PR_REFSEPEC = "${ghprbPullId != null ? SSPL_GPR_REFSEPEC : SSPL_BRANCH_REFSEPEC}"
        
        //////////////////////////////// BUILD VARS //////////////////////////////////////////////////
        // OS_VERSION and COMPONENTS_BRANCH are manually created parameters in jenkins job.

        COMPONENT_NAME = "sspl".trim()
        BRANCH = "${ghprbTargetBranch != null ? ghprbTargetBranch : COMPONENTS_BRANCH}"
        THIRD_PARTY_VERSION = "${OS_VERSION}-2.0.0-latest"
        VERSION = "2.0.0"
        PASSPHARASE = credentials('rpm-sign-passphrase')

        // Artifacts root location

        // 'WARNING' - rm -rf command used on this path please careful when updating this value
        DESTINATION_RELEASE_LOCATION = "/mnt/bigstorage/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"
        PYTHON_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/python-deps/python-packages-2.0.0-latest"
        THIRD_PARTY_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/centos/${THIRD_PARTY_VERSION}/"
        COMPONENTS_RPM = "/mnt/bigstorage/releases/cortx/components/github/${BRANCH}/${OS_VERSION}/dev/"
        CORTX_BUILD = "http://cortx-storage.colo.seagate.com/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"

        // Artifacts location
        CORTX_ISO_LOCATION = "${DESTINATION_RELEASE_LOCATION}/cortx_iso"
        THIRD_PARTY_LOCATION = "${DESTINATION_RELEASE_LOCATION}/3rd_party"
        PYTHON_LIB_LOCATION = "${DESTINATION_RELEASE_LOCATION}/python_deps"

        ////////////////////////////////// DEPLOYMENT VARS /////////////////////////////////////////////////////

        STAGE_DEPLOY = "yes"
    }

    stages {

        // Build s3server fromm PR source code
        stage('Build') {
            steps {
				script { build_stage = env.STAGE_NAME }
                script { manager.addHtmlBadge("&emsp;<b>Target Branch : ${BRANCH}</b>&emsp;<br />") }
                sh """
                    set +x
                    echo "--------------BUILD PARAMETERS -------------------"
                    echo "SSPL_URL              = ${SSPL_URL}"
                    echo "SSPL_BRANCH           = ${SSPL_BRANCH}"
                    echo "SSPL_PR_REFSEPEC      = ${SSPL_PR_REFSEPEC}"
                    echo "-----------------------------------------------------------"
                """
                 
                dir("cortx-monitor") {

                    checkout([$class: 'GitSCM', branches: [[name: "${SSPL_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'AuthorInChangelog'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CloneOption', depth: 1, honorRefspec: true, noTags: true, reference: '', shallow: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: "${SSPL_URL}",  name: 'origin', refspec: "${SSPL_PR_REFSEPEC}"]]])

                    sh label: '', script: '''
                        rm -rf /root/SSPL_RPMS
                        rm -rf /root/*.html
                        yum install -y autoconf automake libtool check-devel doxygen rpm-build gcc openssl-devel graphviz python-pep8 python36-devel libffi-devel
                        sed -i 's/gpgcheck=1/gpgcheck=0/' /etc/yum.conf
                    '''
                    sh label: 'Build', script: '''
                        VERSION=$(cat VERSION)
                        export build_number=${BUILD_ID}
                        #Execute build script
                        echo "Executing build script"
                        echo "VERSION:$VERSION"
                        ./jenkins/build.sh -v $VERSION -l DEBUG
                    '''	
                }
            }
        }

                // Release cortx deployment stack
        stage('Release') {
            steps {
				script { build_stage = env.STAGE_NAME }

                dir('cortx-re') {
                    checkout([$class: 'GitSCM', branches: [[name: '*/main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 1, honorRefspec: true, noTags: true, reference: '', shallow: true], [$class: 'AuthorInChangelog']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-re']]])
                }

                // Install tools required for release process
                sh label: 'Installed Dependecies', script: '''
                    yum install -y expect rpm-sign rng-tools python3-pip
                    systemctl start rngd
                '''

                // Integrate components rpms
                sh label: 'Collect Release Artifacts', script: '''
                    
                    rm -rf "${DESTINATION_RELEASE_LOCATION}"
                    mkdir -p "${DESTINATION_RELEASE_LOCATION}"

                    if [[ ( ! -z `ls /root/rpmbuild/RPMS/x86_64/*.rpm `)]]; then
                        mkdir -p "${CORTX_ISO_LOCATION}"
                        cp /root/rpmbuild/RPMS/x86_64/*.rpm "${CORTX_ISO_LOCATION}"
                        cp /root/rpmbuild/RPMS/noarch/*.rpm "${CORTX_ISO_LOCATION}"
                    else
                        echo "RPM not exists !!!"
                        exit 1
                    fi 

                    pushd ${COMPONENTS_RPM}
                        for component in `ls -1 | grep -E -v "${COMPONENT_NAME}"`
                        do
                            echo -e "Copying RPM's for $component"
                            if ls $component/last_successful/*.rpm 1> /dev/null 2>&1; then
                                cp $component/last_successful/*.rpm "${CORTX_ISO_LOCATION}"
                            fi
                        done
                    popd

                    # Symlink 3rdparty repo artifacts
                    ln -s "${THIRD_PARTY_DEPS}" "${THIRD_PARTY_LOCATION}"
                        
                    # Symlink python dependencies
                    ln -s "${PYTHON_DEPS}" "${PYTHON_LIB_LOCATION}"
                '''

                sh label: 'RPM Signing', script: '''
                    pushd cortx-re/scripts/rpm-signing
                        cat gpgoptions >>  ~/.rpmmacros
                        sed -i 's/passphrase/'${PASSPHARASE}'/g' genkey-batch
                        gpg --batch --gen-key genkey-batch
                        gpg --export -a 'Seagate'  > RPM-GPG-KEY-Seagate
                        rpm --import RPM-GPG-KEY-Seagate
                    popd

                    pushd cortx-re/scripts/rpm-signing
                        chmod +x rpm-sign.sh
                        cp RPM-GPG-KEY-Seagate ${CORTX_ISO_LOCATION}
                        for rpm in `ls -1 ${CORTX_ISO_LOCATION}/*.rpm`
                        do
                            ./rpm-sign.sh ${PASSPHARASE} ${rpm}
                        done
                    popd

                '''
                
                sh label: 'RPM Signing', script: '''
                    pushd ${CORTX_ISO_LOCATION}
                        rpm -qi createrepo || yum install -y createrepo
                        createrepo .
                    popd
                '''	

                sh label: 'RPM Signing', script: '''
                    pushd cortx-re/scripts/release_support
                        sh build_readme.sh "${DESTINATION_RELEASE_LOCATION}"
                        sh build_release_info.sh -v ${VERSION} -l ${CORTX_ISO_LOCATION} -t ${THIRD_PARTY_LOCATION}
                    popd

                    cp "${THIRD_PARTY_LOCATION}/THIRD_PARTY_RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"
                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"

                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" .
                '''		

                archiveArtifacts artifacts: "RELEASE.INFO", onlyIfSuccessful: false, allowEmptyArchive: true
            }

        }

        // Deploy Cortx-Stack
        stage('Deploy') {
            agent { 
                node { 
                    label params.HOST == "-" ? "vm_deployment_1n_7_9 && !cleanup_req" : "vm_deployment_1n_user_host"
                    customWorkspace "/var/jenkins/mini_provisioner/${JOB_NAME}_${BUILD_NUMBER}"
                } 
            }
            when { expression { env.STAGE_DEPLOY == "yes" } }
            environment {
                // Credentials used to SSH node
                NODE_DEFAULT_SSH_CRED =  credentials("${NODE_DEFAULT_SSH_CRED}")
                NODE_USER = "${NODE_DEFAULT_SSH_CRED_USR}"
                NODE1_HOST = "${HOST == '-' ? NODE1_HOST : HOST }"
                NODES = "${NODE1_HOST}"
                NODE_PASS = "${HOST_PASS == '-' ? NODE_DEFAULT_SSH_CRED_PSW : HOST_PASS}"

                NODE_UN_PASS_CRED_ID = "mini-prov-change-pass"
                SETUP_TYPE = "single"
            }
            steps {
                script { build_stage = env.STAGE_NAME }
                script {

                    // Cleanup Workspace
                    cleanWs()

                    markNodeforCleanup()

                    manager.addHtmlBadge("&emsp;<b>Deployment Host :</b><a href='${JENKINS_URL}/computer/${env.NODE_NAME}'> ${NODE1_HOST}</a>&emsp;")

                    // Run Deployment
                    catchError {
                        
                        dir('cortx-re') {
                            checkout([$class: 'GitSCM', branches: [[name: '*/main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 1, honorRefspec: true, noTags: true, reference: '', shallow: true], [$class: 'AuthorInChangelog']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-re']]])
                        }

                        runAnsible("00_PREPARE, 01_DEPLOY_PREREQ, 02_1_PRVSNR_BOOTSTRAP, 02_2_PLATFORM_SETUP, 02_3_PREREQ, 02_4_UTILS, 02_5_IO_PATH, 02_6_CONTROL_PATH, 02_7_HA, 02_DEPLOY_VALIDATE, 03_VALIDATE")

                    }

                    // Collect logs from test node
                    catchError {

                        sh label: 'download_log_files', returnStdout: true, script: """ 
                        mkdir -p artifacts/srvnode1 
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/log/seagate/cortx/ha artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/log/cluster artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/log/pacemaker.log artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/log/pcsd/pcsd.log artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/log/seagate/provisioner artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/opt/seagate/cortx_configs/provisioner_cluster.json artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/var/lib/hare/cluster.yaml artifacts/srvnode1 &>/dev/null || true
                        sshpass -p '${NODE_PASS}' scp -r -o StrictHostKeyChecking=no ${NODE_USER}@${NODE1_HOST}:/root/cortx_deployment artifacts/srvnode1 &>/dev/null || true
                        """
                        
                        archiveArtifacts artifacts: "artifacts/**/*.*", onlyIfSuccessful: false, allowEmptyArchive: true 
                    }

                    hctlStatus = ""
                    if ( fileExists('artifacts/srvnode1/cortx_deployment/log/hctl_status.log') && currentBuild.currentResult == "SUCCESS" ) {
                        hctlStatus = readFile(file: 'artifacts/srvnode1/cortx_deployment/log/hctl_status.log')
                        MESSAGE = "Cortx Stack VM Deployment Success"
                        ICON = "accept.gif"
                        STATUS = "SUCCESS"
                    } else {
                        manager.buildFailure()
                        MESSAGE = "Cortx Stack VM Deployment Failed"
                        ICON = "error.gif"
                        STATUS = "FAILURE"
                    }

                    hctlStatusHTML = "<textarea rows=20 cols=200 readonly style='margin: 0px; height: 392px; width: 843px;'>${hctlStatus}</textarea>"
                    tableSummary = "<table border='1' cellspacing='0' cellpadding='0' width='400' align='left'> <tr> <td align='center'>Branch/Commit</td><td align='center'>${SSPL_BRANCH}</td></tr><tr> <td align='center'>Deploy VM</td><td align='center'>${NODE1_HOST}</td></tr></table>"
                    manager.createSummary("${ICON}").appendText("<h3>${MESSAGE}.</h3><p>Please check <a href=\"${BUILD_URL}/artifact/setup.log\">setup.log</a> for more info <br /><br /><h4>Test Details:</h4> ${tableSummary} <br /><br /><br /><h4>Cluster Status:${hctlStatusHTML}</h4> ", false, false, false, "red")


                    if ( "${HOST}" == "-" ) {
                        if ( "${DEBUG}" == "yes" ) {  
                            markNodeOffline("SSPL Debug Mode Enabled on This Host  - ${BUILD_URL}")
                        } else {
                            build job: 'Cortx-Automation/Deployment/VM-Cleanup', wait: false, parameters: [string(name: 'NODE_LABEL', value: "${env.NODE_NAME}")]                    
                        }
                    }
                    
                    // Cleanup Workspace
                    cleanWs()
                }
            }
        }

	}

    post {
        always {
            script {
                sh label: 'Remove artifacts', script: '''rm -rf "${DESTINATION_RELEASE_LOCATION}"'''
            }
        }
        failure {
            script {
                manager.addShortText("${build_stage} Failed")
            }  
        }
    }
}	


// Method returns VM Host Information ( host, ssh cred)
def getTestMachine(host, user, pass) {

    def remote = [:]
    remote.name = 'cortx'
    remote.host = host
    remote.user =  user
    remote.password = pass
    remote.allowAnyHosts = true
    remote.fileTransfer = 'scp'
    return remote
}


// Used Jenkins ansible plugin to execute ansible command
def runAnsible(tags) {
    
    dir("cortx-re/scripts/deployment") {
        ansiblePlaybook(
            playbook: 'cortx_deploy_vm.yml',
            inventory: 'inventories/vm_deployment/hosts_srvnodes',
            tags: "${tags}",
            extraVars: [
                "HOST"          : [value: "${NODES}", hidden: false],
                "CORTX_BUILD"   : [value: "${CORTX_BUILD}", hidden: false] ,
                "CLUSTER_PASS"  : [value: "${NODE_PASS}", hidden: false],
                "SETUP_TYPE"    : [value: "${SETUP_TYPE}", hidden: false],
            ],
            extras: '-v',
            colorized: true
        )
    }
}
def markNodeforCleanup() {
	nodeLabel = "cleanup_req"
    node = getCurrentNode(env.NODE_NAME)
	node.setLabelString(node.getLabelString() + " " + nodeLabel)
	node.save()
    node = null
}

def getCurrentNode(nodeName) {
  for (node in Jenkins.instance.nodes) {
      if (node.getNodeName() == nodeName) {
        echo "Found node for $nodeName"
        return node
    }
  }
  throw new Exception("No node for $nodeName")
}

// Make failed node offline
def markNodeOffline(message) {
    node = getCurrentNode(env.NODE_NAME)
    computer = node.toComputer()
    computer.setTemporarilyOffline(true)
    computer.doChangeOfflineCause(message)
    computer = null
    node = null
}

def getBuild(buildURL) {

    buildID = sh(script: "curl -s  $buildURL/RELEASE.INFO  | grep BUILD | cut -d':' -f2 | tr -d '\"' | xargs", returnStdout: true).trim()
    buildBranch = sh(script: "curl -s  $buildURL/RELEASE.INFO  | grep BRANCH | cut -d':' -f2 | tr -d '\"' | xargs", returnStdout: true).trim()

 return "$buildBranch#$buildID"   
}