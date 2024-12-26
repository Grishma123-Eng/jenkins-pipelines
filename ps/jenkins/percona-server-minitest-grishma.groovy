/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@add-minitest-support', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Grishma123-Eng/jenkins-pipelines.git'
]) _

import groovy.transform.Field

void installCli(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip
        elif [ ${PLATFORM} = "rpm" ]; then
            export RHVER=\$(rpm --eval %rhel)
            if [ \${RHVER} = "7" ]; then
                sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || true
                sudo sed -i 's|#\\s*baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || true
                if [ -e "/etc/yum.repos.d/CentOS-SCLo-scl.repo" ]; then
                    cat /etc/yum.repos.d/CentOS-SCLo-scl.repo
                fi
            fi
            sudo yum -y install wget curl unzip
        fi
        curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
        unzip awscliv2.zip
        sudo ./aws/install || true
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        if [ ${FIPSMODE} = "YES" ]; then
            sed -i 's|percona-server-server/usr|percona-server-server-pro/usr|g' ps_builder.sh
            sed -i 's|dbg-package=percona-server-dbg|dbg-package=percona-server-pro-dbg|g' ps_builder.sh
        fi
        cat ps_builder.sh
        grep "percona-server-*" ps_builder.sh
        echo "ps_builder ::::"
        export build_dir=\$(pwd -P)
        if [ "$DOCKER_OS" = "none" ]; then
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-8.0.properties ]; then
                . ./test/percona-server-8.0.properties
            fi
            sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            if [ ${BUILD_TOKUDB_TOKUBACKUP} = "ON" ]; then
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            else
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            fi
        else
            docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                if [ -f ./test/percona-server-8.0.properties ]; then
                    . ./test/percona-server-8.0.properties
                fi
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                else
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                fi"
        fi
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
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
       // slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Server Provision for Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!")
    }

}
def runPlaybook(def nodeName) {
    try {

        script {
            env.PS_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
            echo "PS_RELEASE : ${env.PS_RELEASE}"
            env.PS_VERSION_KEY=  sh(script: """echo ${PS_RELEASE} | awk -F'.' '{print \$1 \".\" \$2}'""", returnStdout: true).trim()
            echo "Version is for : ${env.PS_VERSION_KEY}"
            env.ps_version = "PS${env.PS_VERSION_KEY.replace('.', '')}"
            echo "Value is : ${env.ps_version}"
        } 
        echo "Using ps_version in another function: ${env.ps_version}"
        def playbook //= "ps_80.yml"
        def playbook_path //= "package-testing/playbooks/${playbook}"
        def client_to_test

        if (env.ps_version == 'PS80') {
            playbook = "ps_80.yml"
            env.client_to_test = "ps80"
        } else if (env.ps_version == 'PS84') {
            playbook = "ps_84.yml"
            env.client_to_test = "ps84"
        } else {
           // playbook = "ps_80.yml"
            echo "Unknown branch"
        }

        playbook_path = "package-testing/playbooks/${playbook}"

        sh '''
            set -xe
            git clone --depth 1 https://github.com/Percona-QA/package-testing
        '''
        sh """
            set -xe
            export install_repo="testing"
            export client_to_test="${env.client_to_test}"
            export check_warning="yes"
            export install_mysql_shell="no"
            ansible-playbook \
            --connection=local \
            --inventory 127.0.0.1, \
            --limit 127.0.0.1 \
            ${playbook_path}
        """
    } catch (Exception e) {
      //  slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!!")
        mini_test_error="True"
    }
}

def minitestNodes = [ 
                        //"min-bullseye-x64",
                         "min-bookworm-x64" ]
                       //"min-centos-7-x64",
                       //"min-ol-8-x64",
                      // "min-focal-x64",
                      // "min-amazon-2-x64",
                      // "min-jammy-x64",
                      // "min-noble-x64",
                       //"min-ol-9-x64"     ]


def package_tests_ps80(def nodes) {

    echo "hello"
    /*def stepsForParallel = [:]
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
    }*/
   // parallel stepsForParallel
}

@Field def mini_test_error = "False"
def AWS_STASH_PATH
/*def product_to_test
if (env.ps_version == 'PS80' || env.ps_version == 'PS84') {
product_to_test = "${env.ps_version}"
}
else {
product_to_test = 'client_test'  // Default value or handle other conditions
}
echo "Product to test: ${product_to_test}" */
//def PS8_RELEASE_VERSION
def install_repo = 'testing'
//def node_to_test = 'min-jammy-x64'
def action_to_test = 'install'
def check_warnings = 'yes'
def install_mysql_shell = 'no'


pipeline {
    agent {
        label 'docker'
    }
    /* environment {
       /* REVISION = ""
        PS_RELEASE = ""
        PS_VERSION_KEY = ""
        ps_version = "" 
        product_to_test = ""
    } */
    

parameters {
        string(defaultValue: 'https://github.com/percona/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-8.0.28-19', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'The TokuDB storage is no longer supported since 8.0.28',
            name: 'BUILD_TOKUDB_TOKUBACKUP')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        choice(
            choices: 'NO\nYES',
            description: 'Prepare packages and tarballs for Centos 7',
            name: 'ENABLE_EL7')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile with ZenFS support?, only affects Ubuntu Hirsute',
            name: 'ENABLE_ZENFS')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases\n#releases-ci',
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
        stage('Preparation') {
            steps {
   

                script {

                    env.PS_RELEASE = sh(script: "echo ${BRANCH} | sed 's/release-//g'", returnStdout: true).trim()
                    echo "PS_RELEASE: ${env.PS_RELEASE}"
                    env.PS_VERSION_KEY = "${env.PS_RELEASE}".split('\\.')[0..1].join('.')
                    echo "PS_VERSION_KEY: ${env.PS_VERSION_KEY}"
                    env.ps_version = "PS${env.PS_VERSION_KEY.replace('.', '')}"
                    echo "ps_version: ${env.ps_version}"
                    def product_to_test
                    if (env.ps_version == 'PS80' ) {
                        product_to_test = "ps_80"
                        echo "product to test is ps80"
                    } 
                    else if (env.ps_version == 'PS84' ) {
                        product_to_test = "ps_84"
                        echo "product to test is ps84"
                    } 
                    else {
                        product_to_test = 'client_test'
                        echo "product to test is client_test"
                    }

                    env.product_to_test = product_to_test
                    echo "Product to test is: ${env.product_to_test}"
                    }
                }
            }
        stage('Run Playbook') {
            steps {
                script {
                    echo "Product to test in playbook: ${product_to_test}"
                }
            }
        }
        stage('Create PS source tarball') {
            agent {
               label 'min-focal-x64'
            }
            steps {
               // slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("none", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-8.0.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-8.0.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    echo "Helloooo"
                 //   AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-8.0.properties', name: 'properties'
              //  pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                //uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
    }
    post {
        success {
            script {
                echo "Hello"
               /* if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                } */
            } 
           // slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Triggering Builds for Package Testing for ${BRANCH} - [${BUILD_URL}]")
            //unstash 'properties'
            unstash 'properties' 
            script {
               // currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
                REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-server-8.0.properties | awk -F '=' '{ print\$2 }'").trim()
                sh "cat test/percona-server-8.0.properties"
                /*PS_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
                echo "PS_RELEASE : ${PS_RELEASE}"
                PS_VERSION_KEY=  sh(script: """echo ${PS_RELEASE} | awk -F'.' '{print \$1 \".\" \$2}'""", returnStdout: true).trim()
                echo "Version is for : ${PS_VERSION_KEY}"
                ps_version = "PS${PS_VERSION_KEY.replace('.', '')}" */
                echo "Revision is: ${REVISION}"
                echo "PS_RELEASE is: ${PS_RELEASE}"
                echo "PS_VERSION_KEY is: ${PS_VERSION_KEY}"
                echo "Value is : ${ps_version}"
                
                if (env.product_to_test == 'PS80') {
                    echo "Running PS80-specific steps"
                } else if (env.product_to_test == 'PS84') {
                    echo "Running PS84-specific steps"
                } else {
                    echo "Running client test"
                }

            // PS8_RELEASE_VERSION = sh(returnStdout: true, script: """ echo ${BRANCH} | sed -nE '/release-(8\\.[0-9]{1})\\..*/s//\\1/p' """).trim()
                if("${ps_version}"){
                    echo "Executing MINITESTS as VALID VALUES FOR PS8_RELEASE_VERSION:${ps_version}"
                    echo "Checking for the Github Repo VERSIONS file changes..."
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        set -x
                        git clone https://jenkins-pxc-cd:$TOKEN@github.com/Percona-QA/package-testing.git
                        cd package-testing
                        git config user.name "jenkins-pxc-cd"
                        git config user.email "it+jenkins-pxc-cd@percona.com"
                        git checkout testing-branch 
                        echo "${ps_version} is the VALUE!!@!"
                        export RELEASE_VER_VAL="${ps_version}"
                        if [[ "\$RELEASE_VER_VAL" =~ ^PS8[0-9]{1}\$ ]]; then
                            echo "\$RELEASE_VER_VAL is a valid version"
                            OLD_REV=\$(cat VERSIONS | grep ${ps_version}_REV | cut -d '=' -f2- )
                            echo "OLD_REV is : \${OLD_REV}"
                            OLD_VER=\$(cat VERSIONS | grep ${ps_version}_VER | cut -d '=' -f2- )
                            echo "OLD_VER is : \${OLD_VER}"
                            sed -i s/${ps_version}_REV=\$OLD_REV/${ps_version}_REV='"'${REVISION}'"'/g VERSIONS
                            sed -i s/${ps_version}_VER=\$OLD_VER/${ps_version}_VER='"'${PS_RELEASE}'"'/g VERSIONS

                        else
                            echo "INVALID PS8_RELEASE_VERSION VALUE: ${ps_version}"
                        fi
                        git diff
                        if [[ -z \$(git diff) ]]; then
                            echo "No changes"
                        else
                            echo "There are changes"
                            git add -A
                        git commit -m "Autocommit: add ${REVISION} and ${PS_RELEASE} for ${ps_version} package testing VERSIONS file."
                            git push
                        fi
                    """
                    }
                    echo "Start Minitests for PS"                
                    package_tests_ps80(minitestNodes)
                    if("${mini_test_error}" == "True"){
                        error "NOT TRIGGERING PACKAGE TESTS AND INTEGRATION TESTS DUE TO MINITEST FAILURE !!"
                    }else {
                        echo "Package tests passed, moving to parallel tasks"
                    }

            parallel(
               /* "Trigger Package Testing Job":{
                    node ( 'docker' ) {
                    script {
                        echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                        build job: 'ps-package-testing-molecule', propagate: false, wait: false, parameters: [string(name: 'product_to_test', value: "${env.product_to_test}"),string(name: 'install_repo', value: "testing"),string(name: 'action_to_test', value: "install"),string(name: 'check_warnings', value: "yes"),string(name: 'install_mysql_shell', value: "no")]
                                                                                                                                            
                        echo "Trigger PMM_PS Github Actions Workflow"
                        
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                            sh """
                                curl -i -v -X POST \
                                    -H "Accept: application/vnd.github.v3+json" \
                                    -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                    "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                                    -d '{"ref":"main","inputs":{"ps_version":"${PS_RELEASE}"}}'
                            """
                        } 
                    }
                    }
                }, */
                
                "Triggering Docker for ARM64":{
                    node ( 'docker-32gb-aarch64' ){
                    script {
                        echo "Pulling Docker image arm64: perconalab/percona-server:${PS_RELEASE}"
                        sh """
                            docker pull perconalab/percona-server:"${PS_RELEASE}"
                            sudo yum install -y curl wget git
                            TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                            ARCH=\$(uname -m)
                            if [ "\$ARCH" = "x86_64" ]; then
                                echo "Detected architecture: x86_64 (AMD64)"
                                wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                            elif [ "\$ARCH" = "aarch64" ]; then
                                echo "Detected architecture: aarch64 (ARM64)"
                                wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-arm64.tar.gz
                            else
                                echo "Unsupported architecture: \$ARCH"
                                exit 1
                            fi
                            sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-arm64.tar.gz -C /usr/local/bin/
                            sudo chmod +x /usr/local/bin/trivy
                            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                            /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL perconalab/percona-server:"${PS_RELEASE}"
                            echo "completed succesfully for arm"
                        """
                    }
                    echo "running test for ARM"
                    script{
                        sh '''
                            # disable THP on the host for TokuDB
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                            chmod +x disable_thp.sh
                            sudo ./disable_thp.sh
                            # run test
                            export PATH=${PATH}:~/.local/bin
                            sudo yum install -y python3 python3-pip
                            rm -rf package-testing
                            git clone https://github.com/Percona-QA/package-testing.git --depth 1
                            cd package-testing/docker-image-tests/ps-arm
                            pip3 install --user -r requirements.txt
                            set -x
                            echo "Checking if /run.sh exists"
                            ls -l ./run.sh
                            chmod +x ./run.sh
                            echo "Running ./run.sh"
                            ./run.sh
                            echo "ran for ARM"
                        '''
                        echo "Run succesfully for arm"
                    }
                }
                },
                "Triggering Docker for amd64":{
                    node ( 'docker' ){
                    script {
                        echo "Pulling Docker image amd64: perconalab/percona-server:${PS_RELEASE}"
                        sh """
                            docker pull perconalab/percona-server:"${PS_RELEASE}"
                            sudo yum install -y curl wget git
                            TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                            ARCH=\$(uname -m)
                            if [ "\$ARCH" = "x86_64" ]; then
                                echo "Detected architecture: x86_64 (AMD64)"
                                wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                            elif [ "\$ARCH" = "aarch64" ]; then
                                echo "Detected architecture: aarch64 (ARM64)"
                                wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-arm64.tar.gz
                            else
                                echo "Unsupported architecture: \$ARCH"
                                exit 1
                            fi
                            sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                            sudo chmod +x /usr/local/bin/trivy
                            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                            /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL perconalab/percona-server:"${PS_RELEASE}"
                            echo "completed succesfully for amd" 
                        """
                    }
                    echo "running the test for AMD"
                    script {
                        sh '''
                            # disable THP on the host for TokuDB
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                            chmod +x disable_thp.sh
                            sudo ./disable_thp.sh
                            # run test
                            export PATH=${PATH}:~/.local/bin
                            sudo yum install -y python3 python3-pip
                            rm -rf package-testing
                            git clone https://github.com/Percona-QA/package-testing.git --depth 1
                            cd package-testing/docker-image-tests/ps
                            pip3 install --user -r requirements.txt
                            echo "Checking if /run.sh exists"
                            ls -l ./run.sh
                            chmod +x ./run.sh
                            echo "Running ./run.sh"
                            ./run.sh
                            echo "ran for AMD"
                        ''' 
                        echo "Run succesfully for amd" 
                    }
                    }
                }
            )
        }        //./run.sh
                else {
                    error "Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB"
            }
        }
            deleteDir() 
        }
        failure {
          //  slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}

