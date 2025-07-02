
    library changelog: false, identifier: "lib@tarball_task", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/grishma123-eng/jenkins-pipelines.git'
    ])

    pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        node_to_test = "${params.server_to_test}"
        install_repo = "${params.install_repo}"
        server_to_test  = "${params.server_to_test}"
        scenario_to_test = "${params.scenario_to_test}"
        REPO_TYPE = "${params.REPO_TYPE}"
        TESTING_BRANCH = "${params.TESTING_BRANCH}"
    }
    parameters {
        choice(
            choices: ['pxb91', 'pxb91','pxb92'],
            description: 'Choose the product version to test',
            name: 'product_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install packages and run the tests',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/grishma123-eng/package-testing.git',
            description: 'repo name',
            name: 'git_repo',
            trim: false
        )
        string(
        defaultValue: 'pxb_tarball',
        description: 'Branch for package-testing repository',
        name: 'TESTING_BRANCH'
        )
        choice(
            choices: [
                'ms-innovation-lts',
                'ps-9x-innovation'
            ],
            description: 'Server to test',
            name: 'server_to_test'
        )
        choice(
            choices: ['ubuntu-jammy', 'ubuntu-jammy-arm', 'ubuntu-focal'],
            description: 'Choose OS (Molecule scenario name) to test',
            name: 'os_to_test'
        )
        choice(
            choices: [
                'install',
                'upgrade',
                'upstream',
                'kmip',
                'kms'
            ],
            description: 'Scenario To Test',
            name: 'scenario_to_test'
        )
        choice(
            choices: ['NORMAL', 'PRO'],
            description: 'Choose the product to test',
            name: 'REPO_TYPE'
        )

    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
    }

        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${server_to_test}-${scenario_to_test}-${REPO_TYPE}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: "${params.TESTING_BRANCH}", url: "${params.git_repo}"
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        installMoleculeBookworm()
                    }
                }
            }
            stage('RUN TESTS') {
                        steps {
                            script {
                                if (scenario_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                } else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${scenario_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                }

                                def envMap = loadEnvFile('.env.ENV_VARS')
                                
                                withEnv(envMap) {
                                    
                                if (REPO_TYPE == 'PRO') {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            script {
                                                moleculeParallelTestPXB(pxbPackageTesting(), "molecule/pxb-package-testing/")
                                            }
                                        }
                                }
                                else {
                                        moleculeParallelTestPXB(pxbPackageTesting(), "molecule/pxb-package-testing/", "${params.os_to_test}")
                                }

                                }

                            }
                        }

                        post {
                            always {

                                script{
                                    //sh "ls -la ."
                                    //sh "mkdir ARTIFACTS && cp *.zip ARTIFACTS/"
                                    //sh "ls -la ARTIFACTS/"
                                    //sh "zip -r ${env.BUILD_NUMBER}-ARTIFACTS.zip ARTIFACTS"
                                    archiveArtifacts artifacts: '*.zip', allowEmptyArchive: true
                                }
                            }
                        }
            }
        }
    }


def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}
