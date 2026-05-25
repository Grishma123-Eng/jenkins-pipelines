library changelog: false, identifier: 'lib@pxc-minitest', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/grishma123-eng/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
            set -o xtrace
            mkdir -p test
            if [ \${FIPSMODE} = "YES" ]; then
                PXC_VERSION_MINOR=\$(curl -s -O \$(echo \${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/\${GIT_BRANCH}/MYSQL_VERSION && cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                if [ \${PXC_VERSION_MINOR} = "0" ]; then
                    PRO_BRANCH="8.0"
                elif [ \${PXC_VERSION_MINOR} = "4" ]; then
                    PRO_BRANCH="8.4"
                else
                    PRO_BRANCH="trunk"
                fi
                curl -L -H "Authorization: Bearer \${TOKEN}" \
                    -H "Accept: application/vnd.github.v3.raw" \
                    -o pxc_builder.sh \
                    "https://api.github.com/repos/percona/percona-xtradb-cluster-private-build/contents/build-ps/pxc_builder.sh?ref=\${PRO_BRANCH}"
            else
                wget \$(echo ${params.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${params.GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
            fi
            export build_dir=\$(pwd -P)
            if [ "${DOCKER_OS}" = "none" ]; then
                cd \${build_dir}
                sudo bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                if [ \${FIPSMODE} = "YES" ]; then
                    git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtradb-cluster-private-build.git percona-xtradb-cluster-private-build
                    mv -f \${build_dir}/percona-xtradb-cluster-private-build/build-ps \${build_dir}/test/.
                fi
                sudo bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${params.GIT_REPO} --branch=${params.GIT_BRANCH} --rpm_release=${params.RPM_RELEASE} --deb_release=${params.DEB_RELEASE} --bin_release=${params.BIN_RELEASE} ${STAGE_PARAM}
            else
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                    set -o xtrace
                    cd \${build_dir}
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                    if [ \${FIPSMODE} = "YES" ]; then
                        git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtradb-cluster-private-build.git percona-xtradb-cluster-private-build
                        mv -f \${build_dir}/percona-xtradb-cluster-private-build/build-ps \${build_dir}/test/.
                    fi
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${params.GIT_REPO} --branch=${params.GIT_BRANCH} --rpm_release=${params.RPM_RELEASE} --deb_release=${params.DEB_RELEASE} --bin_release=${params.BIN_RELEASE} ${STAGE_PARAM}"
            fi
        """
    }
}

def installDependencies(def nodeName) {
    def aptNodes = ['min-jammy-x64']
   // def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-focal-x64', 'min-jammy-x64', 'min-noble-x64']
 //   def yumNodes = ['min-ol-8-x64',  'min-ol-9-x64', 'min-amazon-2-x64']
     def yumNodes = ['min-ol-8-x64']
    try{
        if (aptNodes.contains(nodeName)) {
            if(nodeName == "min-bullseye-x64" || nodeName == "min-bookworm-x64"){            
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y ansible git wget
                '''
            }else if(nodeName == "min-focal-x64" || nodeName == "min-jammy-x64" || nodeName == "min-noble-x64"){
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y software-properties-common
                    sudo apt-add-repository --yes --update ppa:ansible/ansible
                    sudo apt-get install -y ansible git wget
                '''
            }else {
                error "Node Not Listed in APT"
            }
        } else if (yumNodes.contains(nodeName)) {

            if(nodeName == "min-centos-7-x64" || nodeName == "min-ol-9-x64"){            
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible git wget tar
                '''
            }else if(nodeName == "min-ol-8-x64"){
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible-2.9.27 git wget tar
                '''
            }else if(nodeName == "min-amazon-2-x64"){
                sh '''
                    sudo amazon-linux-extras install epel
                    sudo yum -y update
                    sudo yum install -y ansible git wget
                '''
            }
            else {
                error "Node Not Listed in YUM"
            }
        } else {
            echo "Unexpected node name: ${nodeName}"
        }
    } catch (Exception e) {
      /*  slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Server Provision for Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!") */
    }

}
def loadPxcPropertiesFromFile() {
    def propsFile = 'test/pxc-80.properties'
    sh "cat ${propsFile}"
    env.PXC_REVISION = sh(returnStdout: true, script: "grep '^REVISION=' ${propsFile} | awk -F '=' '{ print \$2 }'").trim()
    env.PXC_INNODB = sh(returnStdout: true, script: "grep '^MYSQL_RELEASE=' ${propsFile} | awk -F '=' '{ print \$2 }'").trim()
    def wsrepVersion = sh(returnStdout: true, script: "grep '^WSREP_VERSION=' ${propsFile} | awk -F '=' '{ print \$2 }'").trim()
    def wsrepRev = sh(returnStdout: true, script: "grep '^WSREP_REV=' ${propsFile} | awk -F '=' '{ print \$2 }'").trim()
    env.PXC_WSREP = "${wsrepVersion.tokenize('.')[0..1].join('.')}(${wsrepRev})"
    def mysqlVersion = sh(returnStdout: true, script: "grep '^MYSQL_VERSION=' ${propsFile} | awk -F '=' '{ print \$2 }'").trim()
    env.PXC_RELEASE = "${mysqlVersion}-${env.PXC_INNODB}"
    env.PXC_VERSION_SHORT_KEY = env.PXC_RELEASE.tokenize('.')[0..1].join('.')
    env.PXC_VERSION_SHORT = "PXC${env.PXC_VERSION_SHORT_KEY.replace('.', '')}"
    env.product_to_test = (env.PXC_VERSION_SHORT == 'PXC84') ? 'pxc84' : 'pxc80'
    echo "PXC_RELEASE: ${env.PXC_RELEASE}"
    echo "PXC_REVISION: ${env.PXC_REVISION}"
    echo "PXC_INNODB: ${env.PXC_INNODB}"
    echo "PXC_WSREP: ${env.PXC_WSREP}"
    echo "PXC_VERSION_SHORT: ${env.PXC_VERSION_SHORT}"
}

def setPxcVersionEnv() {
    def branch = params.GIT_BRANCH.trim()
    def release = branch.replaceAll('^release-', '')
    env.PXC_RELEASE = release
    def parts = release.tokenize('.')
    env.PXC_VERSION_SHORT_KEY = parts.size() >= 2 ? parts[0..1].join('.') : release
    env.PXC_VERSION_SHORT = "PXC${env.PXC_VERSION_SHORT_KEY.replace('.', '')}"
    env.product_to_test = (env.PXC_VERSION_SHORT == 'PXC84') ? 'pxc84' : 'pxc80'
    echo "GIT_BRANCH=${branch} -> PXC_RELEASE=${env.PXC_RELEASE}, PXC_VERSION_SHORT=${env.PXC_VERSION_SHORT}, product_to_test=${env.product_to_test}"
}

def runPlaybook(def nodeName) {
    script {
            if (!env.PXC_VERSION_SHORT) {
                setPxcVersionEnv()
            }
            echo "Minitest on ${nodeName}: PXC_VERSION_SHORT=${env.PXC_VERSION_SHORT}, product_to_test=${env.product_to_test}"
            def playbook = (env.PXC_VERSION_SHORT == 'PXC84') ? 'pxc84_bootstrap.yml' : 'pxc80_bootstrap.yml'
            def playbook_path = "package-testing/playbooks/${playbook}"
            def clientLabel = env.product_to_test
            sh '''
                set -xe
                git clone --depth 1 https://github.com/Percona-QA/package-testing
            '''
            def exitCode = sh(
                script: """
                    set -xe
                    export install_repo="${install_repo}"
                    export client_to_test="${clientLabel}"
                    export check_warnings="${check_warnings}"
                    export install_mysql_shell="${install_mysql_shell}"
                    ansible-playbook \
                        --connection=local \
                        --inventory 127.0.0.1, \
                        --limit 127.0.0.1 \
                        ${playbook_path}
                """,
                returnStatus: true
            )
            if (exitCode != 0) {
                error "Ansible playbook failed on ${nodeName} with exit code ${exitCode}"
            }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def minitestNodes = [  "min-jammy-x64",
                       "min-ol-8-x64" ,  
                    ]


def package_tests_pxc80(def nodes) {
    def stepsForParallel = [:]
    for (int i = 0; i < nodes.size(); i++) {
        def nodeName = nodes[i]
        stepsForParallel[nodeName] = {
            stage("Minitest run on ${nodeName}") {
                node(nodeName) {
                        installDependencies(nodeName)
                        runPlaybook(nodeName)
                }
            }
        }
    }
    parallel stepsForParallel
}

def AWS_STASH_PATH
def product_to_test = ''
def install_repo = 'testing'
def action_to_test = 'install'
def check_warnings = 'yes'
def install_mysql_shell = 'no'
def DOCKER_ACC = "perconalab"
env.DOCKER_ACC = DOCKER_ACC

// Register parameters at script load so Jenkins shows "Build with Parameters"
properties([
    parameters([
        choice(name: 'CLOUD', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build'),
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git', description: 'URL for percona-xtradb-cluster repository'),
        string(name: 'GIT_BRANCH', defaultValue: 'release-8.0.45-36', description: 'PXC git branch (e.g. 8.0 or release-8.0.45-36)'),
        string(name: 'RPM_RELEASE', defaultValue: '1', description: 'RPM release value'),
        string(name: 'DEB_RELEASE', defaultValue: '1', description: 'DEB release value'),
        string(name: 'BIN_RELEASE', defaultValue: '1', description: 'BIN release value'),
        booleanParam(name: 'SKIP_OL10', defaultValue: false, description: 'Skips packages for OL10'),
        booleanParam(name: 'SKIP_TRIXIE', defaultValue: false, description: 'Skips packages for Debian 13'),
        choice(name: 'FIPSMODE', choices: ['NO', 'YES'], description: 'Enable fipsmode'),
        choice(name: 'COMPONENT', choices: ['testing', 'experimental', 'laboratory'], description: 'Repo component to push packages to'),
        choice(name: 'SLACKNOTIFY', choices: ['#releases-ci', '#releases'], description: 'Channel for notifications'),
    ])
])

pipeline {
    agent {
        label 'docker-32gb'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PXC source tarball') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
            }
            steps {
           /*     slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]") */
                cleanUpWS()
                script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--get_sources=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--get_sources=1")
                            }
                       }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "DEST=UPLOAD" test/pxc-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pxc-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    env.AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                stash includes: 'uploadPath', name: 'uploadPath'
               // pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                //uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        /*
        stage('Build PXC generic source packages') {
            parallel {
                stage('Build PXC generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXC generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:xenial", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:xenial", "--build_source_deb=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PXC RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 8') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                            buildStage("centos:8", "--build_rpm=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Centos 8 ARM') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                            buildStage("centos:8", "--build_rpm=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    when {
                        expression { !env.SKIP_OL10.toBoolean() }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:10", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:10", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    when {
                        expression { !env.SKIP_OL10.toBoolean() }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:10", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:10", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                            buildStage("amazonlinux:2023", "--build_rpm=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                            buildStage("amazonlinux:2023", "--build_rpm=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                            buildStage("debian:bullseye", "--build_deb=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                            buildStage("debian:bullseye", "--build_deb=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)') {
                    when {
                        expression { !env.SKIP_TRIXIE.toBoolean() }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:trixie", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:trixie", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    when {
                        expression { !env.SKIP_TRIXIE.toBoolean() }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:trixie", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:trixie", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8 tarball') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                            buildStage("centos:8", "--build_tarball=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "test/tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS(params.CLOUD, "test/tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS(params.CLOUD, "test/tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Debian Bullseye(11) tarball') {
                    when {
                        expression { env.FIPSMODE == 'NO' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            unstash 'pxc-80.properties'
                            popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                            buildStage("debian:bullseye", "--build_tarball=1")

                            stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                            pushArtifactFolder(params.CLOUD, "test/tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS(params.CLOUD, "test/tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS(params.CLOUD, "test/tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                script {
                    PXC_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${GIT_BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    if ("${PXC_VERSION_MINOR}" == "0") {
                    // sync packages
                        if (env.FIPSMODE == 'YES') {
                            sync2PrivateProdAutoBuild(params.CLOUD, "pxc-80-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild(params.CLOUD, "pxc-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2PrivateProdAutoBuild(params.CLOUD, "pxc-84-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild(params.CLOUD, "pxc-8x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2ProdAutoBuild(params.CLOUD, "pxc-84-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild(params.CLOUD, "pxc-8x-innovation", COMPONENT)
                            }
                        }
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        if (env.FIPSMODE == 'YES') {
                            uploadTarballToDownloadsTesting(params.CLOUD, "pxc-gated", "${GIT_BRANCH}")
                        } else {
                            uploadTarballToDownloadsTesting(params.CLOUD, "pxc", "${GIT_BRANCH}")
                        }
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Build docker containers') {
            when {
                expression { env.FIPSMODE == 'NO' }
            }
            agent {
                label 'launcher-x64'
            }
            steps {
                script {
                    build job: 'hetzner-pxc8.0-docker-build',
                          parameters: [
                              string(name: 'CLOUD', value: 'Hetzner'),
                              string(name: 'ORGANIZATION', value: 'perconalab'),
                              string(name: 'GIT_BRANCH', value: "${params.GIT_BRANCH}"),
                              string(name: 'RPM_RELEASE', value: '1'),
                              string(name: 'DEB_RELEASE', value: '1'),
                              string(name: 'FIPSMODE', value: 'NO'),
                              booleanParam(name: 'RUN_FAST', value: true)
                          ],
                          wait: false
                }
            }
        } */
    }
    post {
        success {
            script {
                echo "testing"
                 currentBuild.description = "Built on ${params.GIT_BRANCH}; path to packages: ${params.COMPONENT}/${env.AWS_STASH_PATH}"
                unstash 'pxc-80.properties'
                loadPxcPropertiesFromFile()
                echo "Revision is: ${env.PXC_REVISION}"
                echo "PXC_RELEASE is: ${env.PXC_RELEASE}"
                echo "PXC_INNODB is: ${env.PXC_INNODB}"
                echo "PXC_WSREP is: ${env.PXC_WSREP}"
                echo "PXC_VERSION_SHORT_KEY is: ${env.PXC_VERSION_SHORT_KEY}"
                echo "Value is : ${env.PXC_VERSION_SHORT}"
                echo "DOCKER account is : ${env.DOCKER_ACC}"

                if (env.product_to_test == 'pxc80') {
                    echo "Running PXC80-specific steps"
                } else if (env.product_to_test == 'pxc84') {
                    echo "Running PXC84-specific steps"
                } else {
                    echo "Running client test"
                }
                 if("${env.PXC_VERSION_SHORT}"){
                    echo "Executing MINITESTS as VALID VALUES FOR PXC8_RELEASE_VERSION:${env.PXC_VERSION_SHORT}"
                    echo "Checking for the Github Repo VERSIONS file changes..."
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        set -x
                        git clone https://jenkins-pxc-cd:$TOKEN@github.com/Percona-QA/package-testing.git
                        cd package-testing
                        git config user.name "jenkins-pxc-cd"
                        git config user.email "it+jenkins-pxc-cd@percona.com"
                        git checkout testing-branch
                        echo "${env.PXC_VERSION_SHORT} is the VALUE!!@!"
                        export RELEASE_VER_VAL="${env.PXC_VERSION_SHORT}"
                        if [[ "\$RELEASE_VER_VAL" =~ ^PXC8[0-9]{1}\$ ]]; then
                            echo "\$RELEASE_VER_VAL is a valid version"
                            OLD_REV=\$(cat VERSIONS | grep ${env.PXC_VERSION_SHORT}_REV | cut -d '=' -f2- )
                            echo "OLD_REV is : \${OLD_REV}"
                            OLD_VER=\$(cat VERSIONS | grep ${env.PXC_VERSION_SHORT}_VER | cut -d '=' -f2- )
                            echo "OLD_VER is : \${OLD_VER}"
                            OLD_INNODB=\$(cat VERSIONS | grep ${env.PXC_VERSION_SHORT}_INNODB | cut -d '=' -f2- )
                            echo "OLD_INNODB is : \${OLD_INNODB}"
                            OLD_WSREP=\$(cat VERSIONS | grep ${env.PXC_VERSION_SHORT}_WSREP | cut -d '=' -f2- )
                            echo "OLD_WSREP is : \${OLD_WSREP}"
                            sed -i "s|^${env.PXC_VERSION_SHORT}_REV=.*|${env.PXC_VERSION_SHORT}_REV='${env.PXC_REVISION}'|g" VERSIONS
                            sed -i "s|^${env.PXC_VERSION_SHORT}_VER=.*|${env.PXC_VERSION_SHORT}_VER='${env.PXC_RELEASE}'|g" VERSIONS
                            sed -i "s|^${env.PXC_VERSION_SHORT}_INNODB=.*|${env.PXC_VERSION_SHORT}_INNODB='${env.PXC_INNODB}'|g" VERSIONS
                            sed -i "s|^${env.PXC_VERSION_SHORT}_WSREP=.*|${env.PXC_VERSION_SHORT}_WSREP='${env.PXC_WSREP}'|g" VERSIONS

                        else
                            echo "INVALID PXC8_RELEASE_VERSION VALUE: ${env.PXC_VERSION_SHORT}"
                        fi
                        git diff
                        if [[ -z \$(git diff) ]]; then
                            echo "No changes"
                        else
                            echo "There are changes"
                            git add -A
                        git commit -m "Autocommit: add ${env.PXC_REVISION}, ${env.PXC_RELEASE}, ${env.PXC_INNODB}, and ${env.PXC_WSREP} for ${env.PXC_VERSION_SHORT} package testing VERSIONS file."
                            git push origin testing-branch
                        fi
                    """
                    }
                    parallel(
                        "Start Minitests for PXC": {
                            try {
                                package_tests_pxc80(minitestNodes)
                                echo "Minitests completed successfully. Triggering next stages."
                                slackNotify("${params.SLACKNOTIFY}", "#FF0000", "[${env.JOB_NAME}]: minitest sucessfully run for ${params.GIT_BRANCH} - [${env.BUILD_URL}]")
                                echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                                build job: 'pxc-package-testing', propagate: false, wait: false, parameters: [
                                    string(name: 'product_to_test', value: "${env.product_to_test}"),
                                    string(name: 'node_to_test', value: 'ubuntu-jammy'),
                                    string(name: 'test_repo', value: 'testing'),
                                    string(name: 'test_type', value: 'install'),
                                    string(name: 'pxc57_repo', value: 'N/A'),
                                    string(name: 'git_repo', value: 'Percona-QA/package-testing'),
                                    string(name: 'BRANCH', value: 'master'),
                                ]
                                echo "Trigger PMM_PS Github Actions Workflow"
                                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                                    sh """
                                        curl -i -v -X POST \
                                            -H "Accept: application/vnd.github.v3+json" \
                                            -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                            "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                                            -d '{"ref":"main","inputs":{"PXC_version":"${PXC_RELEASE}"}}'
                                    """
                                }
                               
                             //   slackNotify("${params.SLACKNOTIFY}", "#FF0000", "[${env.JOB_NAME}]: PMM sucessfully run for ${params.GIT_BRANCH} - [${env.BUILD_URL}]")
                            } catch (err) {
                                echo " Minitests block failed: ${err}"
                                currentBuild.result = 'FAILURE'
                                throw err
                            }
                        }
                    )
                } else {
                    error "Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB"
                    slackNotify("${params.SLACKNOTIFY}", "#00FF00", "[${env.JOB_NAME}]: Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB ${params.GIT_BRANCH} - [${env.BUILD_URL}]")
                }
            }
            deleteDir()
        }
        failure {
            script {
                 echo "testing"
               /* if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: PRO build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                }*/
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "PRO -> Built on ${params.GIT_BRANCH} - packages [${params.COMPONENT}/${env.AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${params.GIT_BRANCH} - packages [${params.COMPONENT}/${env.AWS_STASH_PATH}]"
                }
            }
            deleteDir()
        }
    }
}