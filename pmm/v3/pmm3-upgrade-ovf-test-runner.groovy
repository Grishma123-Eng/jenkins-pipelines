library changelog: false, identifier: 'lib@PMM-14156', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

void checkClientBeforeUpgrade(String PMM_SERVER_VERSION, String CLIENT_VERSION) {
    def PMM_VERSION = CLIENT_VERSION.trim();
    env.PMM_VERSION = PMM_VERSION;
    if (PMM_VERSION == '3-dev-latest') {
        sh '''
            GET_PMM_CLIENT_VERSION=$(wget -q https://raw.githubusercontent.com/Percona-Lab/pmm-submodules/v3/VERSION -O -)
            sudo chmod 755 /srv/pmm-qa/support_scripts/check_client_upgrade.py
            python3 /srv/pmm-qa/support_scripts/check_client_upgrade.py ${GET_PMM_CLIENT_VERSION}
        '''
    } else if (PMM_VERSION == 'pmm3-rc') {
        sh '''
            GET_PMM_CLIENT_VERSION=$(wget -q "https://registry.hub.docker.com/v2/repositories/perconalab/pmm-client/tags?page_size=25&name=rc" -O - | jq -r .results[].name  | grep 3.*.*-rc$ | sort -V | tail -n1)
            sudo chmod 755 /srv/pmm-qa/support_scripts/check_client_upgrade.py
            python3 /srv/pmm-qa/support_scripts/check_client_upgrade.py ${GET_PMM_CLIENT_VERSION}
        '''
    } else {
        sh '''
            sudo chmod 755 /srv/pmm-qa/support_scripts/check_client_upgrade.py
            python3 /srv/pmm-qa/support_scripts/check_client_upgrade.py ${PMM_VERSION}
        '''
    }
}

void runOVFStagingStart(SERVER_VERSION, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, ENABLE_EXPERIMENTAL_REPO) {
    ovfStagingJob = build job: 'pmm2-ovf-staging-start', parameters: [
        string(name: 'OVA_VERSION', value: SERVER_VERSION),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ENABLE_EXPERIMENTAL_REPO', value: ENABLE_EXPERIMENTAL_REPO)
    ]
    env.SERVER_IP = ovfStagingJob.buildVariables.PUBLIC_IP
    env.OVF_INSTANCE_IP = ovfStagingJob.buildVariables.PUBLIC_IP
    env.ADMIN_PASSWORD = 'admin'
    env.VM_IP = ovfStagingJob.buildVariables.PUBLIC_IP
    env.VM_NAME = ovfStagingJob.buildVariables.VM_NAME
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
    env.PMM_UI_URL = "https://${SERVER_IP}/"
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
    sh """
        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${SERVER_IP} 'bash -c "
            sudo docker network create --driver=bridge pmm-qa || true
            echo \\"PMM_DEBUG=1\\" >> /home/admin/.config/systemd/user/pmm-server.env
            echo \\"PMM_ENABLE_TELEMETRY=0\\" >> /home/admin/.config/systemd/user/pmm-server.env
            echo \\"PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX\\" >> /home/admin/.config/systemd/user/pmm-server.env
            echo \\"PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com\\" >> /home/admin/.config/systemd/user/pmm-server.env
            echo \\"PMM_DEV_UPDATE_DOCKER_IMAGE=$DOCKER_TAG_UPGRADE\\" >> /home/admin/.config/systemd/user/pmm-server.env
            cat /home/admin/.config/systemd/user/pmm-server.env

            systemctl --user restart pmm-server
            docker network create pmm-qa || true
            docker network connect pmm-qa pmm-server
            docker network connect pmm-qa watchtower
        "'
    """
  }
}

def versionsList = pmmVersion('v3-ami')
def amiVersions = versionsList.values()
def versions = versionsList.keySet()
def upgradeAmiVersion = amiVersions[0]
def latestVersion = versions[versions.size() - 1]
def upgradeVersion = versions[0]

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    environment {
        REMOTE_AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        REMOTE_AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        REMOTE_AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
        REMOTE_MYSQL_HOST=credentials('mysql-remote-host')
        REMOTE_MYSQL_USER=credentials('mysql-remote-user')
        REMOTE_MYSQL_PASSWORD=credentials('mysql-remote-password')
        GCP_SERVER_IP=credentials('GCP_SERVER_IP')
        GCP_USER=credentials('GCP_USER')
        GCP_USER_PASSWORD=credentials('GCP_USER_PASSWORD')
        REMOTE_MONGODB_HOST=credentials('qa-remote-mongodb-host')
        REMOTE_MONGODB_USER=credentials('qa-remote-mongodb-user')
        REMOTE_MONGODB_PASSWORD=credentials('qa-remote-mongodb-password')
        REMOTE_POSTGRESQL_HOST=credentials('qa-remote-pgsql-host')
        REMOTE_POSTGRESQL_USER=credentials('qa-remote-pgsql-user')
        REMOTE_POSTGRESSQL_PASSWORD=credentials('qa-remote-pgsql-password')
        REMOTE_PROXYSQL_HOST=credentials('qa-remote-proxysql-host')
        REMOTE_PROXYSQL_USER=credentials('qa-remote-proxysql-user')
        REMOTE_PROXYSQL_PASSWORD=credentials('qa-remote-proxysql-password')
        INFLUXDB_ADMIN_USER=credentials('influxdb-admin-user')
        INFLUXDB_ADMIN_PASSWORD=credentials('influxdb-admin-password')
        INFLUXDB_USER=credentials('influxdb-user')
        INFLUXDB_USER_PASSWORD=credentials('influxdb-user-password')
        MONITORING_HOST=credentials('monitoring-host')
        PMM_QA_AURORA2_MYSQL_HOST=credentials('PMM_QA_AURORA2_MYSQL_HOST')
        PMM_QA_AURORA2_MYSQL_PASSWORD=credentials('PMM_QA_AURORA2_MYSQL_PASSWORD')
        PMM_QA_AWS_ACCESS_KEY_ID=credentials('PMM_QA_AWS_ACCESS_KEY_ID')
        PMM_QA_AWS_ACCESS_KEY=credentials('PMM_QA_AWS_ACCESS_KEY')
        MAILOSAUR_API_KEY=credentials('MAILOSAUR_API_KEY')
        MAILOSAUR_SERVER_ID=credentials('MAILOSAUR_SERVER_ID')
        MAILOSAUR_SMTP_PASSWORD=credentials('MAILOSAUR_SMTP_PASSWORD')
        ZEPHYR_PMM_API_KEY=credentials('ZEPHYR_PMM_API_KEY')
    }
    parameters {
        string(
            defaultValue: 'PMM-14156',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: upgradeVersion,
            description: 'PMM Server Version to test for Upgrade (Docker Tag, AMI ID or OVF version)',
            name: 'AMI_TAG')
        string(
            defaultValue: '',
            description: 'PMM Server Version to upgrade to, if empty docker tag will be used from version service.',
            name: 'DOCKER_TAG_UPGRADE')
        string(
            defaultValue: '3.0.0',
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ["experimental", "testing", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
        choice(
            choices: ["SSL", "EXTERNAL SERVICES", "MONGO BACKUP", "CUSTOM PASSWORD", "CUSTOM DASHBOARDS", "ANNOTATIONS-PROMETHEUS", "ADVISORS-ALERTING", "SETTINGS-METRICS"],
            description: 'Subset of tests for the upgrade',
            name: 'UPGRADE_FLAG')
    }
    options {
        skipDefaultCheckout()
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
                    env.PMM_UI_URL = params.PMM_UI_URL
//                     currentBuild.description = "${env.UPGRADE_FLAG} - Upgrade for PMM from ${env.DOCKER_TAG.split(":")[1]} to ${env.PMM_SERVER_LATEST}."
                }
                git poll: false,
                    branch: PMM_UI_GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    cd  /srv/pmm-qa
                        sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                    sudo mkdir -p /srv/qa-integration || true
                    cd  /srv/qa-integration
                        sudo git clone --single-branch --branch \${QA_INTEGRATION_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git .
                    sudo chmod -R 755 /srv/qa-integration
                    sudo chown $(id -u):$(id -u) -R /srv/qa-integration
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
                '''
            }
        }
        stage('Select subset of tests') {
            steps {
                script {
                    if (env.UPGRADE_FLAG == "SSL") {
                        env.PRE_UPGRADE_FLAG = "@pre-ssl-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-ssl-upgrade"
                        env.PMM_CLIENTS = "--database ssl_psmdb --database ssl_mysql --database ssl_pdpgsql"
                    } else if (env.UPGRADE_FLAG == "EXTERNAL SERVICES") {
                        env.PRE_UPGRADE_FLAG = "@pre-external-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-external-upgrade"
                        env.PMM_CLIENTS = "--database external --database ps --database pdpgsql --database psmdb --database pxc"
                    } else if (env.UPGRADE_FLAG == "MONGO BACKUP") {
                        env.PRE_UPGRADE_FLAG = "@pre-mongo-backup-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-mongo-backup-upgrade"
                        env.PMM_CLIENTS = "--database psmdb,SETUP_TYPE=pss"
                    } else if (env.UPGRADE_FLAG == "CUSTOM PASSWORD") {
                        env.PRE_UPGRADE_FLAG = "@pre-custom-password-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-custom-password-upgrade"
                        env.PMM_CLIENTS = "--database ps --database pgsql --database psmdb"
                    } else if (env.UPGRADE_FLAG == "CUSTOM DASHBOARDS") {
                        env.PRE_UPGRADE_FLAG = "@pre-dashboards-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-dashboards-upgrade"
                        env.PMM_CLIENTS = "--help"
                    } else if (env.UPGRADE_FLAG == "ANNOTATIONS-PROMETHEUS") {
                        env.PRE_UPGRADE_FLAG = "@pre-annotations-prometheus-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-annotations-prometheus-upgrade"
                        env.PMM_CLIENTS = "--database ps --database pgsql --database psmdb"
                    } else if (env.UPGRADE_FLAG == "ADVISORS-ALERTING") {
                        env.PRE_UPGRADE_FLAG = "@pre-advisors-alerting-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-advisors-alerting-upgrade"
                        env.PMM_CLIENTS = "--help"
                    } else if (env.UPGRADE_FLAG == "SETTINGS-METRICS") {
                        env.PRE_UPGRADE_FLAG = "@pre-settings-metrics-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-settings-metrics-upgrade"
                        env.PMM_CLIENTS = "--database pgsql --database ps --database psmdb"
                    }
                }
            }
        }
        stage('Start AMI server Instance') {
            steps {
                runOVFStagingStart(AMI_TAG, PMM_QA_GIT_BRANCH)
            }
        }

        stage('PMM Server sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -k -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Setup Dependencies and PMM Client') {
            parallel {
                stage('Setup PMM Client') {
                    steps {
                        setupPMM3Client(SERVER_IP, CLIENT_VERSION.trim(), 'pmm', 'no', 'no', 'no', 'upgrade', 'admin', 'no')
                    }
                }
                stage('Setup dependencies') {
                    steps {
                        sh '''
                            npm ci
                            npx playwright install
                            envsubst < env.list > env.generated.list
                            sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                            export PWD=$(pwd)
                            export CHROMIUM_PATH=/usr/bin/chromium
                            ansible-galaxy collection install ansible.utils
                        '''
                    }
                }
            }
        }
        stage('Setup Databases for PMM-Server') {
            steps {
                sh '''
                    set -o errexit
                    set -o xtrace

                    pushd /srv/qa-integration/pmm_qa
                    echo "Setting docker based PMM clients"
                    mkdir -m 777 -p /tmp/backup_data
                    python3 -m venv virtenv
                    . virtenv/bin/activate
                    pip install --upgrade pip
                    pip install -r requirements.txt
                    pip install netaddr
                    pip install setuptools

                    python pmm-framework.py --verbose \
                        --pmm-server-ip=\${SERVER_IP}
                        --client-version=\${CLIENT_VERSION} \
                        --pmm-server-password=\${ADMIN_PASSWORD} \
                        \${PMM_CLIENTS}
                    popd
                '''
            }
        }
        stage('Setup Custom queries') {
            steps {
                script {
                    if (env.UPGRADE_FLAG == "SETTINGS-METRICS") {
                        sh '''
                            containers=$(docker ps -a)
                            echo $containers
                            psContainerName=$(docker ps -a --format "{{.Names}}" | grep "ps_pmm")
                            echo "$psContainerName"
                            pgsqlContainerName=$(docker ps -a --format "{{.Names}}" | grep "pgsql_pg")
                            echo "$pgsqlContainerName"
                            echo "Creating Custom Queries"
                            git clone https://github.com/Percona-Lab/pmm-custom-queries
                            docker cp pmm-custom-queries/mysql/. $psContainerName:/usr/local/percona/pmm/collectors/custom-queries/mysql/high-resolution/
                            echo "Adding Custom Queries for postgres"
                            docker cp pmm-custom-queries/postgresql/. $pgsqlContainerName:/usr/local/percona/pmm/collectors/custom-queries/postgresql/high-resolution/
                            echo 'node_role{role="my_monitored_server_1"} 1' > node_role.prom
                            sudo cp node_role.prom /usr/local/percona/pmm/collectors/textfile-collector/high-resolution/
                            docker exec $psContainerName pkill -f mysqld_exporter
                            docker exec $pgsqlContainerName pkill -f postgres_exporter
                            docker exec $pgsqlContainerName pmm-admin list
                            docker exec $psContainerName pmm-admin list
                            sudo pkill -f node_exporter
                            sleep 5
                            echo "Setup for Custom Queries Completed along with custom text file collector Metrics"
                            docker ps -a --format "{{.Names}}"

                            docker exec $psContainerName pmm-admin list | grep mysqld_exporter

                            psAgentId=$(docker exec $psContainerName pmm-admin list | grep mysqld_exporter | awk -F' ' '{ print $4 }')
                            psAgentPort=$(docker exec $psContainerName pmm-admin list | grep mysqld_exporter | awk -F' ' '{ print $6 }')
                            echo $psAgentPort
                        '''
                    }
                }
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
        stage('Check PMM Server Packages before Upgrade') {
            steps {
                script {
                    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh '''
                            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${AMI_INSTANCE_IP} "bash -c '
                                export PMM_VERSION=$(curl --location -k --user admin:\${ADMIN_PASSWORD} \${PMM_UI_URL}v1/server/version | jq -r \'.version\')
                                echo \\${PMM_VERSION}
                                echo "PMM Version is: \\${PMM_VERSION}"
                                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
                                python3 /srv/pmm-qa/support_scripts/check_upgrade.py -v \\$PMM_VERSION -p pre
                                '
                            "
                        '''
                    }
                }
            }
        }
        stage('Check Client before Upgrade') {
            steps {
                script {
                    checkClientBeforeUpgrade(PMM_SERVER_LATEST, CLIENT_VERSION)
                }
            }
        }
        stage('Run pre upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep \${PRE_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Run UI upgrade') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep '@pmm-upgrade'
                    '''
                }
            }
        }
        stage('Run post pmm server upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep ${POST_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Upgrade PMM client') {
            steps {
                sh """
                   containers=\$(docker ps --format "{{ .Names }}")

                   for i in \$containers; do
                       if [[ \$i == *"rs10"* ]]; then
                           docker exec rs101 percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec rs101 dnf install -y pmm-client
                           docker exec rs101 systemctl restart pmm-agent
                       elif [[ \$i == *"mysql_"* ]]; then
                           docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec \$i apt install -y pmm-client
                           mysql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                           docker exec \$i kill \$mysql_process_id
                           docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                       elif [[ \$i == *"pdpgsql"* ]]; then
                           docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec \$i apt install -y pmm-client
                           pdpgsql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                           docker exec \$i kill \$pdpgsql_process_id
                           docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                       elif [[ \$i == *"pgsql"* ]]; then
                           docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec \$i apt install -y pmm-client
                           pgsql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                           docker exec \$i kill \$pgsql_process_id
                           docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                       elif [[ \$i == *"ps_"* ]]; then
                           docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec \$i apt install -y pmm-client
                           ps_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                           docker exec \$i kill \$ps_process_id
                           docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                       elif [[ \$i == *"external_pmm"* ]]; then
                           docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                           docker exec \$i apt install -y pmm-client
                           ps_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                           docker exec \$i kill \$ps_process_id
                           docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                       fi
                   done
                """
            }
        }
        stage('Run post pmm client upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep \${POST_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Check PMM Server Packages after Upgrade') {
            steps {
                script {
                    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh '''
                            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${AMI_INSTANCE_IP} "bash -c '
                                export PMM_VERSION=$(curl --location -k --user admin:\${ADMIN_PASSWORD} \${PMM_UI_URL}v1/server/version | jq -r \'.version\')
                                echo \\${PMM_VERSION}
                                echo "PMM Version is: \\${PMM_VERSION}"
                                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
                                python3 /srv/pmm-qa/support_scripts/check_upgrade.py -v \\$PMM_VERSION -p post
                                '
                            "
                        '''
                    }
                }
            }
        }
    }
    post {
        always {
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                amiStagingStopJob = build job: 'pmm3-ami-staging-stop', parameters: [
                    string(name: 'AMI_ID', value: env.AMI_INSTANCE_ID),
                ]
            }
        }
        failure {
            archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
        }
    }
}
