library changelog: false, identifier: 'lib@pxc_test', retriever: modernSCM([
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
              wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
          fi
          pwd -P
          ls -laR
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

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def runPlaybook(def nodeName) {
        script {
            env.PXC_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
            echo "PXC_RELEASE : ${env.PXC_RELEASE}"
            env.PXC_VERSION_SHORT_KEY=  sh(script: """echo ${PXC_RELEASE} | awk -F'.' '{print \$1 \".\" \$2}'""", returnStdout: true).trim()
            echo "Version is : ${env.PXC_VERSION_SHORT_KEY}"
            env.PXC_VERSION_SHORT = "PS${env.PXC_VERSION_SHORT_KEY.replace('.', '')}"
            echo "Value is : ${env.PXC_VERSION_SHORT}"
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
                    export client_to_test="PXC80"
                    export check_warning="\${check_warnings}"
                    export install_mysql_shell="\${install_mysql_shell}"
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
def minitestNodes =   [  "min-bullseye-x64",
                         "min-bookworm-x64",
                         "min-ol-8-x64",
                         "min-jammy-x64",
                         "min-noble-x64",
                         "min-ol-9-x64"]

def package_tests_PXC80(def nodes) {
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

@Field def mini_test_error = "False"
def AWS_STASH_PATH
def product_to_test = ''
def test_repo = 'testing'
def pxc57_repo = 'orignal'
def test_type = 'install'
def test_type_pro = 'install'
def pro_repo = 'no'
def git_repo = ''
def BRANCH = ''

pipeline {
    agent {
        label 'docker-32gb'
    }
    parameters {
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
        choice(
            choices: 'pxc-80\npxc-8x-innovation\npxc-84-lts',
            description: 'PXC repo name',
            name: 'PXC_REPO')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
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
                  //  env.DOCKER_ACC= 'perconalab'
                    env.PXC_RELEASE = sh(script: "echo ${BRANCH} | sed 's/release-//g'", returnStdout: true).trim()
                    echo "PXC_RELEASE: ${env.PXC_RELEASE}"
                    env.PXC_VERSION_SHORT_KEY = "${env.PXC_RELEASE}".split('\\.')[0..1].join('.')
                    echo "PXC_VERSION_SHORT_KEY: ${env.PXC_VERSION_SHORT_KEY}"
                    env.PXC_VERSION_SHORT = "PS${env.PXC_VERSION_SHORT_KEY.replace('.', '')}"
                    echo "PXC_VERSION_SHORT: ${env.PXC_VERSION_SHORT}"
                    if (env.PXC_VERSION_SHORT == 'PXC84') {
                        product_to_test = 'pxc84_bootstrap.yml'
                    } 
                    else {
                        product_to_test = 'pxc80_bootstrap.yml'
                    }
                    echo "Product to test is: ${product_to_test}"
                }
            }
        }
        stage('Create PXC source tarball') {
            steps {
             /*   slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]") */
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
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PXC generic source packages') {
            parallel {
                stage('Build PXC generic source rpm') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXC generic source deb') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:xenial", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:xenial", "--build_source_deb=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PXC RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        echo "The step is skipped"
/*
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
*/
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("centos:8", "--build_rpm=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 8 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("centos:8", "--build_rpm=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("centos:8", "--build_tarball=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Debian Bullseye(11) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                unstash 'pxc-80.properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_tarball=1")

                                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
                        }

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
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
     /*   stage('Push to public repository') {
            steps {
                script {
                    PXC_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${GIT_BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    if ("${PXC_VERSION_MINOR}" == "0") {
                    // sync packages
                        if (env.FIPSMODE == 'YES') {
                            sync2PrivateProdAutoBuild("pxc-80-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild("pxc-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2PrivateProdAutoBuild("pxc-84-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild("pxc-8x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2ProdAutoBuild("pxc-84-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild("pxc-8x-innovation", COMPONENT)
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
                            uploadTarballToDownloadsTesting("pxc-gated", "${GIT_BRANCH}")
                        } else {
                            uploadTarballToDownloadsTesting("pxc", "${GIT_BRANCH}")
                        }
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        } */
        }
    }

    post {
        success {
            unstash 'properties'
            script {
                echo "post success start now"
                currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
                env.PXC_REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-xtradb-cluster-8.0.properties| awk -F '=' '{ print\$2 }'").trim()
                sh "cat test/percona-xtradb-cluster-8.0.properties"
                echo "Revision is: ${env.PXC_REVISION}"
                echo "PXC_RELEASE is: ${PXC_RELEASE}"
                echo "PXC_VERSION_SHORT_KEY is: ${PXC_VERSION_SHORT_KEY}"
                echo "Value is : ${PXC_VERSION_SHORT}"
                echo "DOCKER account is : ${DOCKER_ACC}"

                if (env.product_to_test == 'PXC80') {
                    echo "Running PXC80-specific steps"
                } else if (env.product_to_test == 'PXC84') {
                    echo "Running PXC84-specific steps"
                } else {
                    echo "Running client test"
                }
                 if("${PXC_VERSION_SHORT}"){
                    echo "Executing MINITESTS as VALID VALUES FOR PXC8_RELEASE_VERSION:${PXC_VERSION_SHORT}"
                    echo "Checking for the Github Repo VERSIONS file changes..."
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        set -x
                        git clone https://jenkins-pxc-cd:$TOKEN@github.com/Percona-QA/package-testing.git
                        git checkout Testing-branch
                        cd package-testing
                        git config user.name "jenkins-pxc-cd"
                        git config user.email "it+jenkins-pxc-cd@percona.com"
                        git checkout master
                        echo "${PXC_VERSION_SHORT} is the VALUE!!@!"
                        export RELEASE_VER_VAL="${PXC_VERSION_SHORT}"
                        if [[ "\$RELEASE_VER_VAL" =~ ^PXC8[0-9]{1}\$ ]]; then
                            echo "\$RELEASE_VER_VAL is a valid version"
                            OLD_REV=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_REV | cut -d '=' -f2- )
                            echo "OLD_REV is : \${OLD_REV}"
                            OLD_VER=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_VER | cut -d '=' -f2- )
                            echo "OLD_VER is : \${OLD_VER}"
                            sed -i s/${PXC_VERSION_SHORT}_REV=\$OLD_REV/${PXC_VERSION_SHORT}_REV='"'${PXC_REVISION}'"'/g VERSIONS
                            sed -i s/${PXC_VERSION_SHORT}_VER=\$OLD_VER/${PXC_VERSION_SHORT}_VER='"'${PXC_RELEASE}'"'/g VERSIONS
                            echo 

                        else
                            echo "INVALID PXC8_RELEASE_VERSION VALUE: ${PXC_VERSION_SHORT}"
                        fi
                        git diff
                        if [[ -z \$(git diff) ]]; then
                            echo "No changes"
                        else
                            echo "There are changes"
                            git add -A
                        git commit -m "Autocommit: add ${PXC_REVISION} and ${PXC_RELEASE} for ${PXC_VERSION_SHORT} package testing VERSIONS file."
                            git push
                        fi
                    """
                    }
            echo "start minitest for pxc"
            try {
                    package_tests_pxc80(minitestNodes)
                    echo "Minitests completed successfully. Triggering next stages."
                  //  slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: minitest sucessfully run for ${BRANCH} - [${BUILD_URL}]")
                    echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                    build job: 'pxc-package-testing-parallel', propagate: false, wait: false, parameters: [string(name: 'product_to_test', value: "${product_to_test}"),string(name: 'test_repo', value: "testing"),string(name: 'pxc57_repo', value: "orignal"),string(name: 'test_type', value: "install"),string(name: 'test_type_pro', value: "install"),string(name: 'pro_repo', value: "no"),string(name: 'git_repo', value: "grishma123-eng/package-testing"),string(name: 'BRANCH', value: "master")]
                    echo "Trigger PMM_PS Github Actions Workflow"
                    withCredentials([string(credentialsId: 'Github_Integration', variable: 'Github_Integration')]) {
                        sh """
                        curl -i -v -X POST \
                        -H "Accept: application/vnd.github.v3+json" \
                        -H "Authorization: token ${Github_Integration}" \
                        "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                        -d '{"ref":"main","inputs":{"pxc_version":"${PXC_RELEASE}"}}'
                        """ 
                        }
           } catch (err) {
                    echo " Minitests block failed: ${err}"
                    currentBuild.result = 'FAILURE'
                    throw err
                   }
               /* if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } */
            }
            deleteDir()
        }
        failure {
            script {
              /*  if (env.FIPSMODE == 'YES') {
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
