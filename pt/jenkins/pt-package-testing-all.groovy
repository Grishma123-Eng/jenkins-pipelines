library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runNodeBuild(String node_to_test) {
    build(
        job: 'pt-package-testing',
        parameters: [
            string(name: 'product_to_test', value: product_to_test),
            string(name: 'install_repo', value: params.install_repo),
            string(name: 'node_to_test', value: node_to_test),
            string(name: 'git_repo', value: params.git_repo),
            string(name: 'git_branch', value: params.git_branch),
            booleanParam(name: 'skip_ps57', value: params.skip_ps57),
            booleanParam(name: 'skip_ps80', value: params.skip_ps80),
            booleanParam(name: 'skip_pxc57', value: params.skip_pxc57),
            booleanParam(name: 'skip_pxc80', value: params.skip_pxc80),
            booleanParam(name: 'skip_upstream57', value: params.skip_upstream57),
            booleanParam(name: 'skip_upstream80', value: params.skip_upstream80),
            booleanParam(name: 'skip_psmdb44', value: params.skip_psmdb44),
            booleanParam(name: 'skip_psmdb50', value: params.skip_psmdb50),
            booleanParam(name: 'skip_psmdb60', value: params.skip_psmdb60)
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
    agent {
        label 'docker'
    }

    parameters {
        choice(
            choices: ['pt3'],
            description: 'Product version to test',
            name: 'product_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install percona toolkit packages from',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: '',
            name: 'git_repo',
            trim: false
        )
        string(
            defaultValue: 'master',
            description: '',
            name: 'git_branch',
            trim: false
        )
        booleanParam(
            name: 'skip_ps57',
            description: "Enable to skip ps 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_ps80',
            description: "Enable to skip ps 8.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_ps84',
            description: "Enable to skip ps 8.4 packages installation tests"
        )
        booleanParam(
            name: 'skip_pxc57',
            description: "Enable to skip pxc 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_pxc80',
            description: "Enable to skip pxc 8.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_pxc84',
            description: "Enable to skip pxc 8.4 packages installation tests"
        )
        booleanParam(
            name: 'skip_psmdb70',
            description: "Enable to skip psmdb 7.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_psmdb80',
            description: "Enable to skip psmdb 8.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_upstream57',
            description: "Enable to skip MySQL 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_upstream80',
            description: "Enable to skip MySQL 8.0 packages installation tests"
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '15'))
        skipDefaultCheckout()
    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.install_repo}-${params.node_to_test}"
                }
            }
        }

        stage('Run parallel') {
            parallel {
                stage('Install') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        runPlaybook("install")
                    }
                }

                stage('Upgrade') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            params.install_repo != 'main'
                        }
                    }
                    steps {
                        runPlaybook("upgrade")
                    }
                }

                stage('ps57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(bullseye|noble)/) && !params.skip_ps57
                        }
                    }
                    environment {
                        install_with = 'ps57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('ps80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(noble)/) && !params.skip_ps80
                        }
                    }
                    environment {
                        install_with = 'ps80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('ps84_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(noble)/) && !params.skip_ps84
                        }
                    }
                    environment {
                        install_with = 'ps84'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('pxc57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(bullseye|noble)/) && !params.skip_pxc57
                        }
                    }
                    environment {
                        install_with = 'pxc57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('pxc80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(noble)/) && !params.skip_pxc80
                        }
                    }
                    environment {
                        install_with = 'pxc80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('pxc84_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(noble)/) && !params.skip_pxc84
                        }
                    }
                    environment {
                        install_with = 'pxc84'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('psmdb70_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(ol-9|bookworm|noble)/) && !params.skip_psmdb70
                        }
                    }
                    environment {
                        install_with = 'psmdb70'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('psmdb80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(ol-9|bookworm|noble)/) && !params.skip_psmdb80
                        }
                    }
                    environment {
                        install_with = 'psmdb80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('upstream57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(ol-8|ol-9|focal|jammy|buster|bullseye|bookworm|noble)/) && !params.skip_upstream57
                        }
                    }
                    environment {
                        install_with = 'upstream57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('upstream80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(buster|bookworm|noble)/) && !params.skip_upstream80
                        }
                    }
                    environment {
                        install_with = 'upstream80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }
            }
        }
    }
}
