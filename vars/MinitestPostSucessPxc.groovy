def call(Map args = [:]) {
    def product_to_test      = args.get('product_to_test', '')
    def PXC_RELEASE           = args.get('PXC_RELEASE', '')
    def PXC_REVISION          = args.get('PXC_REVISION', '')
    def PXC_INNODB            = args.get('PXC_INNODB', '')
    def PXC_WSREP             = args.get('PXC_WSREP', '')
    def PXC_VERSION_SHORT     = args.get('PXC_VERSION_SHORT', '')
    def PXC_VERSION_SHORT_KEY = args.get('PXC_VERSION_SHORT_KEY', '')
    def minitestNodes        = args.get('minitestNodes', [])
    def SLACKNOTIFY          = args.get('SLACKNOTIFY', '')
    def GIT_BRANCH           = args.get('GIT_BRANCH', '')
    def DOCKER_ACC           = args.get('DOCKER_ACC', '')
    def packageTestsClosure  = args.get('packageTestsClosure', null)
    def dockerTestClosure    = args.get('dockerTestClosure', null)

    echo "Starting post-success logic..."
    echo "PXC_RELEASE: ${PXC_RELEASE}"
    echo "PXC_REVISION: ${PXC_REVISION}"
    echo "PXC_INNODB: ${PXC_INNODB}"
    echo "PXC_WSREP: ${PXC_WSREP}"
    echo "PXC_VERSION_SHORT_KEY: ${PXC_VERSION_SHORT_KEY}"
    echo "PXC_VERSION_SHORT: ${PXC_VERSION_SHORT}"
    echo "Docker Account: ${DOCKER_ACC}"

    if (product_to_test == 'pxc80') {
        echo "Running PXC80-specific steps"
    } else if (product_to_test == 'pxc84') {
        echo "Running PXC84-specific steps"
    } else {
        echo "Running client test"
    }

    if ("${PXC_VERSION_SHORT}") {
        echo "Executing MINITESTS as VALID VALUES FOR PXC8_RELEASE_VERSION:${PXC_VERSION_SHORT}"
        echo "Checking for the Github Repo VERSIONS file changes..."

        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
            sh """
                set -x
                git clone https://jenkins-pxc-cd:\${TOKEN}@github.com/percona-qa/package-testing.git
                cd package-testing
                git config user.name "jenkins-pxc-cd"
                git config user.email "it+jenkins-pxc-cd@percona.com"
                echo "${PXC_VERSION_SHORT} is the VALUE!!@!"
                export RELEASE_VER_VAL="${PXC_VERSION_SHORT}"
                if [[ "\$RELEASE_VER_VAL" =~ ^PXC8[0-9]{1}\$ ]]; then
                    echo "\$RELEASE_VER_VAL is a valid version"
                    OLD_REV=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_REV | cut -d '=' -f2- )
                    echo "OLD_REV is : \${OLD_REV}"
                    OLD_VER=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_VER | cut -d '=' -f2- )
                    echo "OLD_VER is : \${OLD_VER}"
                    OLD_INNODB=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_INNODB | cut -d '=' -f2- )
                    echo "OLD_INNODB is : \${OLD_INNODB}"
                    OLD_WSREP=\$(cat VERSIONS | grep ${PXC_VERSION_SHORT}_WSREP | cut -d '=' -f2- )
                    echo "OLD_WSREP is : \${OLD_WSREP}"
                    sed -i "s|^${PXC_VERSION_SHORT}_REV=.*|${PXC_VERSION_SHORT}_REV='${PXC_REVISION}'|g" VERSIONS
                    sed -i "s|^${PXC_VERSION_SHORT}_VER=.*|${PXC_VERSION_SHORT}_VER='${PXC_RELEASE}'|g" VERSIONS
                    sed -i "s|^${PXC_VERSION_SHORT}_INNODB=.*|${PXC_VERSION_SHORT}_INNODB='${PXC_INNODB}'|g" VERSIONS
                    sed -i "s|^${PXC_VERSION_SHORT}_WSREP=.*|${PXC_VERSION_SHORT}_WSREP='${PXC_WSREP}'|g" VERSIONS
                else
                    echo "INVALID PXC8_RELEASE_VERSION VALUE: ${PXC_VERSION_SHORT}"
                fi
                git diff
                if [[ -z \$(git diff) ]]; then
                    echo "No changes"
                else
                    echo "There are changes"
                    git add -A
                    git commit -m "Autocommit: add ${PXC_REVISION}, ${PXC_RELEASE}, ${PXC_INNODB}, and ${PXC_WSREP} for ${PXC_VERSION_SHORT} package testing VERSIONS file."
                    git push
                fi
            """
        }

        if (packageTestsClosure && dockerTestClosure) {
            parallel(
                "Start Minitests for PXC": {
                    try {
                        packageTestsClosure(minitestNodes)
                        echo "Minitests completed successfully. Triggering next stages."
                        echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                        build job: 'pxc-package-testing', propagate: false, wait: false, parameters: [
                            string(name: 'product_to_test', value: "${product_to_test}"),
                            string(name: 'node_to_test', value: 'ubuntu-jammy'),
                            string(name: 'test_repo', value: 'testing'),
                            string(name: 'test_type', value: 'install'),
                            string(name: 'pxc57_repo', value: 'N/A'),
                            string(name: 'git_repo', value: 'grishma123-eng/package-testing'),
                            string(name: 'BRANCH', value: 'PS-97'),
                        ]
                    } catch (err) {
                        echo " Minitests block failed: ${err}"
                        currentBuild.result = 'FAILURE'
                        throw err
                    }
                },
                "Start Docker job": {
                    try {
                        dockerTestClosure()
                        echo "Docker images run successfully."
                    } catch (err) {
                        echo " Docker test block failed: ${err}"
                        currentBuild.result = 'FAILURE'
                        throw err
                    }
                }
            )
        } else {
            error "packageTestsClosure and dockerTestClosure must be provided"
        }

    } else {
        error "Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB ${GIT_BRANCH}"
    }

    deleteDir()
}