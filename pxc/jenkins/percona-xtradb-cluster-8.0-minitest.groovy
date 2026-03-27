library changelog: false, identifier: 'lib@pxc-minitest', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/grishma23-eng/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        withCredentials([usernamePassword(credentialsId: 'PXC_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
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
                    sed -i "s/PRIVATE_USERNAME/\${USERNAME}/g" pxc_builder.sh
                    sed -i "s/PRIVATE_TOKEN/\${PASSWORD}/g" pxc_builder.sh
                else
                    wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
                fi
                pwd -P
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                    set -o xtrace
                    cd \${build_dir}
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                    if [ \${FIPSMODE} = "YES" ]; then
                        git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtradb-cluster-private-build.git percona-xtradb-cluster-private-build
                        mv -f \${build_dir}/percona-xtradb-cluster-private-build/build-ps \${build_dir}/test/.
                    fi
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --bin_release=${BIN_RELEASE} ${STAGE_PARAM}"
            """
        }
    }
}

def installDependencies(def nodeName) {
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-focal-x64', 'min-jammy-x64', 'min-noble-x64']
    def yumNodes = ['min-ol-8-x64', 'min-centos-7-x64', 'min-ol-9-x64', 'min-amazon-2-x64']
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
def runPlaybook(def nodeName) {
    script {
            env.PXC_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
            echo "PXC_RELEASE : ${env.PXC_RELEASE}"
            env.PXC_VERSION_SHORT_KEY=  sh(script: """echo ${PXC_RELEASE} | awk -F'.' '{print \$1 \".\" \$2}'""", returnStdout: true).trim()
            echo "Version is : ${env.PXC_VERSION_SHORT_KEY}"
            echo "fetching docker version: \$fetched_docker_version"   
            env.PXC_VERSION_SHORT = "PS${env.PXC_VERSION_SHORT_KEY.replace('.', '')}"
            echo "Value is : ${env.PXC_VERSION_SHORT}"
            echo "Run succesfully for amd"         
            echo "Using PXC_VERSION_SHORT in another function: ${env.PXC_VERSION_SHORT}"
            def playbook
            if (env.PXC_VERSION_SHORT == 'PXC80') {
                playbook = "pxc80_bootstrap.yml"
            } else {
                playbook = "pxc84_bootstrap.yml"
            }
            def client_to_test = PXC_VERSION_SHORT
            def playbook_path = "package-testing/playbooks/${playbook}"
            sh '''
                set -xe
                git clone --depth 1 https://github.com/Percona-QA/package-testing
            '''
            def exitCode = sh(
                script: """
                    set -xe
                    export install_repo="\${install_repo}"
                    echo "ran succesfully for amd docker trivy"   
                    export client_to_test="ps80"
                    export check_warning="\${check_warnings}"
                    export install_mysql_shell="${env.INSTALL_MYSQL_SHELL}"
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

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-amazon-2-x64",
                       "min-jammy-x64",
                       "min-noble-x64",
                       "min-ol-9-x64"  ,
                       "min-ol-9-x64" ,  
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
def BRANCH_NAME = env.BRANCH ?: "release-8.0.43-34"
def PXC_RELEASE = BRANCH_NAME.replaceAll("release-", "")
def PXC_VERSION_SHORT_KEY = PXC_RELEASE.tokenize('.')[0..1].join('.')
def PXC_VERSION_SHORT = "PS${PXC_VERSION_SHORT_KEY.replace('.', '')}"
/*def DOCKER_ACC = "perconalab"*/
product_to_test = (PXC_VERSION_SHORT == 'PXC84') ? 'pxc_84' : 'pxc_80'
env.P_RELEASE = PXC_RELEASE
env.PXC_VERSION_SHORT_KEY = PXC_VERSION_SHORT_KEY
env.PXC_VERSION_SHORT = PXC_VERSION_SHORT
env.DOCKER_ACC = DOCKER_ACC
env.product_to_test = product_to_test

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-xtradb-cluster repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: '1',
            description: 'BIN release value',
            name: 'BIN_RELEASE')
        booleanParam(
            defaultValue: false,
            description: "Skips packages for OL10",
            name: 'SKIP_OL10'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips packages for Debian 13",
            name: 'SKIP_TRIXIE'
        )
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'testing\nexperimental\nlaboratory',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
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
                        buildStage("centos:7", "--get_sources=1 --enable_fipsmode=1")
                    } else {
                        buildStage("centos:7", "--get_sources=1")
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
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
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
                              string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),
                              string(name: 'RPM_RELEASE', value: '1'),
                              string(name: 'DEB_RELEASE', value: '1'),
                              string(name: 'FIPSMODE', value: 'NO'),
                              booleanParam(name: 'RUN_FAST', value: true)
                          ],
                          wait: false
                }
            }
        }
    }
    post {
        success {
            script {
                echo "testing"
             /*   if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } */
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
                    currentBuild.description = "PRO -> Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                }
            }
            deleteDir()
        }
    }
}