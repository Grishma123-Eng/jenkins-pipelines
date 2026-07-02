library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
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
                docker run --shm-size=16g --cap-add=SYS_NICE -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
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
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-jammy-x64', 'min-noble-x64']
    def yumNodes = ['min-ol-8-x64',  'min-ol-9-x64', 'min-amazon-2-x64']

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
                git clone --depth 1 -b PS-97 https://github.com/grishma123-eng/package-testing
            '''
            def exitCode = sh(
                script: """
                    set -xe

                    export product_to_test="${env.product_to_test}"
                    export node_to_test="${nodeName}"
                    export test_repo="${test_repo}"
                    export install_repo="${test_repo}"
                    export pro="no"
                    export pxc57_repo="${pxc57_repo}"
                    export test_type="${test_type}"
                    export git_repo="${params.GIT_REPO}"
                    export BRANCH="${params.GIT_BRANCH}"

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
                       "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-noble-x64",
                       "min-ol-8-x64" ,  
                       "min-ol-9-x64",
                       "min-amazon-2-x64"
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
def docker_test() {
    def stepsForParallel = [:]
    stepsForParallel['Run for ARM64'] = {
        node('docker-32gb-aarch64') {
            stage('Run trivy analyzer ARM') {
                script {
                    sh "sudo yum install -y wget git"
                    sh '''
                        set -e
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sudo sh -s -- -b /usr/local/bin
                        wget -q https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                    '''
                    sh """
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-arm.xml \
                        --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/${DOCKER_PRODUCT}:${DOCKER_TAG} || true
                    """
                }
            }
            stage('Run docker tests ARM') {
                script {
                    sh """
                        sudo rm -rf package-testing
                        git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                    """
                    sh '''
                        cd package-testing/docker-image-tests/pxc-arm
                        # Patch upstream test bugs at runtime (package-testing repo is NOT modified):
                        # 1) over-indented "if" right after def test_install_component
                        sed -i '/def test_install_component/{n;s/^            if /        if /}' tests/test_pxc_cluster.py
                        # 2) settings.py missing pxc_components assignment for the 8.4 branch
                        grep -q "pxc_components = pxc84_components" settings.py || sed -i 's/    pxc_functions = pxc84_functions/    pxc_functions = pxc84_functions\\n    pxc_components = pxc84_components/' settings.py
                    '''
                    sh """
                        export PATH=\${PATH}:~/.local/bin
                        sudo yum install -y python3 python3-pip
                        cd package-testing/docker-image-tests/pxc-arm
                        pip3 install --user -r requirements.txt
                        export DOCKER_ACC="${DOCKER_ACC}"
                        export DOCKER_PRODUCT="${DOCKER_PRODUCT}"
                        export DOCKER_TAG="${DOCKER_TAG}"
                        export PXC_VERSION="${PXC_VERSION}"
                        export PXC_REVISION="${PXC_REVISION}"
                        export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                        ./run.sh
                    """
                }
            }
        }
    }
    stepsForParallel['Run all tests on AMD'] = {
        node('docker-32gb') {
            stage('Run trivy analyzer AMD') {
                script {
                    sh "sudo yum install -y wget git"
                    sh '''
                        set -e
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sudo sh -s -- -b /usr/local/bin
                        wget -q https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                    '''
                    sh """
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-amd.xml \
                        --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/${DOCKER_PRODUCT}:${DOCKER_TAG} || true
                    """
                }
            }
            stage('Run docker tests AMD') {
                script {
                    sh """
                        sudo rm -rf package-testing
                        git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                    """
                    sh '''
                        cd package-testing/docker-image-tests/pxc
                        # Patch upstream bug at runtime (package-testing repo is NOT modified):
                        # settings.py missing pxc_components assignment for the 8.4 branch
                        grep -q "pxc_components = pxc84_components" settings.py || sed -i 's/    pxc_functions = pxc84_functions/    pxc_functions = pxc84_functions\\n    pxc_components = pxc84_components/' settings.py
                    '''
                    sh """
                        export PATH=\${PATH}:~/.local/bin
                        sudo yum install -y python3 python3-pip
                        cd package-testing/docker-image-tests/pxc
                        pip3 install --user -r requirements.txt
                        export DOCKER_ACC="${DOCKER_ACC}"
                        export DOCKER_PRODUCT="${DOCKER_PRODUCT}"
                        export DOCKER_TAG="${DOCKER_TAG}"
                        export PXC_VERSION="${PXC_VERSION}"
                        export PXC_REVISION="${PXC_REVISION}"
                        export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                        ./run.sh
                    """
                }
            }
        }
    }
    parallel stepsForParallel
}
void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH
def product_to_test = ''
def node_to_test = ''
test_repo = 'testing'
action_to_test = 'install'
pxc57_repo = 'N/A'
test_type = 'install'
def git_repo = ''
def DOCKER_ACC = "perconalab"
env.DOCKER_ACC = DOCKER_ACC


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
               label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
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
                script {
                    sh """
                        curl -s \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/MYSQL_VERSION -o MYSQL_VERSION
                    """
                    env.MYSQL_VERSION_MINOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MINOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    echo "Detected PXC version minor: ${env.MYSQL_VERSION_MINOR}"
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
                stage('Ubuntu Resolute(26.04)') {
                    when {
                        expression { env.MYSQL_VERSION_MINOR == '4' }
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
                                buildStage("ubuntu:resolute", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:resolute", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Resolute(26.04) ARM') {
                    when {
                        expression { env.MYSQL_VERSION_MINOR == '4' }
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
                                buildStage("ubuntu:resolute", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:resolute", "--build_deb=1")
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
                stage('Debian Trixie(13) tarball') {
                    when {
                        expression { !env.SKIP_TRIXIE.toBoolean() }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_tarball=1")

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
                currentBuild.description = "Built on ${params.GIT_BRANCH}; path to packages: ${params.COMPONENT}/${env.AWS_STASH_PATH}"
                unstash 'pxc-80.properties'
                loadPxcPropertiesFromFile()

                // Variables consumed by docker_test() (exported into the docker-image-tests run.sh)
                env.DOCKER_PRODUCT             = 'percona-xtradb-cluster'
                env.DOCKER_TAG                 = "${env.PXC_RELEASE}.${params.RPM_RELEASE}"
                env.PXC_VERSION                = env.PXC_RELEASE
                env.PXC_WSREP_VERSION          = env.PXC_WSREP
                env.PACKAGE_TESTING_REPO_URL    = 'https://github.com/Percona-QA/package-testing.git'
                env.PACKAGE_TESTING_REPO_BRANCH = 'master'

                MinitestPostSucessPxc(
                    product_to_test: env.product_to_test,
                    PXC_RELEASE: env.PXC_RELEASE,
                    PXC_REVISION: env.PXC_REVISION,
                    PXC_INNODB: env.PXC_INNODB,
                    PXC_WSREP: env.PXC_WSREP,
                    PXC_VERSION_SHORT: env.PXC_VERSION_SHORT,
                    PXC_VERSION_SHORT_KEY: env.PXC_VERSION_SHORT_KEY,
                    minitestNodes: minitestNodes,
                    SLACKNOTIFY: params.SLACKNOTIFY,
                    GIT_BRANCH: params.GIT_BRANCH,
                    DOCKER_ACC: env.DOCKER_ACC,
                    packageTestsClosure: { nodes -> package_tests_pxc80(nodes) },
                    dockerTestClosure: { -> docker_test() }
                )
            }
        }
        failure {
            script {
                if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: PRO build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                }
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
