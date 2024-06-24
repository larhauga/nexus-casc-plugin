# Nexus Configuration as Code

Nexus CasC is a configuration as code plugin for sonatype nexus 3.

This plugin allows to specify a YAML file to configure a Nexus instance on startup.

This repository maintains a fork with releases kept in track with the nexus releases.

## Why Fork?

Forked from: <https://github.com/AdaptiveConsulting/nexus-casc-plugin>
Who forked from: <https://github.com/sventschui/nexus-casc-plugin>

The original provider was unable to maintain the project

### Changes from the fork

* The groupId has changed to avoid clashing with the original project
* The build process now produces a `.kar` archive and can be directly deployed in
  upstream Nexus via the `deploy` directory (providing the API versions match)
* Unit tests and integration tests have been enabled
* Basic CI has been added
* Releases are currently pushed to our private repository, this may change in the future

## Building

Requires:

* Java 8 JDK (OpenJDK is fine)
* Maven (3.6.3 or higher preferred)
* docker-compose (only if running the integration tests)

To just build the `.kar` archive:

```bash
./mvnw package
```

To build the `.kar` archive and execute the (limited!) integration tests via `docker-compose`:

```bash
./mvnw verify
```

## Usage

**Warning**: Use the project version that matches your Nexus version.
This is because the project is tied to specific version of the Nexus API and there is no guarantee
the API remains consistent (although it usually does).

Deploy the .kar archive using the upstream `sonatype/nexus3` image in the `/opt/sonatype/nexus/deploy/` directory.
The plugin will be automatically installed on startup.

It expects a YAML configuration file to be mounted to `/opt/nexus.yml` (This path can be overridden using the `NEXUS_CASC_CONFIG` env var).

The format of the YAML file is documented below.

Start Nexus as usual.

## Local testing

There is a `docker-compose.yml` file with all the necessary to test this. The test admin user is `johndoe` and its password
is located in the `password_johndoe` file. The YAML configuration is located in `default-nexus.yml`. It has a non-working
proxy configuration just to test its configuration. Removing `httpProxy` and/or `httpsProxy` entirely will also clear the relevant
proxy settings on the next boot.

```shell
./mvnw package
docker-compose run nexus
```

## Configuration file

You can find an example configuration file [here](https://github.com/AdaptiveConsulting/nexus-casc-plugin/blob/master/default-nexus.yml).

### Interpolation

Use `${ENV_VAR}` for env var interpolation. Use `${ENV_VAR:default}` or `${ENV_VAR:"default"}` for default values.

Use `${file:/path/to/a/file}` to include the contents of a file.

The configuration file supports following options:

### Supported options

#### Core

```yaml
core:
  baseUrl: "" # Nexus base URL
  userAgentCustomization: ""
  connectionTimeout: 0 # ignored if 0
  connectionRetryAttempts: 0 # ignored if 0
  httpProxy: # HTTP proxy
    host: ""
    port: 80 # defaults to 80
    username: ""
    password: ""
    ntlmHost: ""
    ntlmDomain: ""
  httpsProxy: # HTTPs proxy
    host: ""
    port: 80 # still defaults to 80 because Nexus has no way to know that it needs to use TLS for the proxy itself
    username: ""
    password: ""
    ntlmHost: ""
    ntlmDomain: ""
  nonProxyHosts: # list of hosts not to be queried through a proxy
    - "host1"
    - "hostn..."
```

#### Security

```yaml
security:
  anonymousAccess: false # Enable/Disable anonymous access
  pruneUsers: true # True to delete users not part of this configuration file
  realms: # Authentication realms, tested for rutauth-realm only
    - name: rutauth-realm
      enabled: true
  users:
    - username: johndoe
      firstName: John
      lastName: Doe
      password: ${file:/run/secrets/password_johndoe}
      updateExistingPassword: false # True to update passwords of existing users, otherwise password is only used when creating a user
      email: johndoe@example.org
      roles:
        - source: ""
          role: nx-admin
```

#### Repository

for any repositories that require authentication it looks like this:

```yaml
repository:
  repositories:
    - name: some-proxy
      attributes:
        httpclient:
          connection:
            blocked: false
            autoBlock: true
          authentication:
            type: username  # or ntlm
            username: test
            password: test
            #ntlmHost: someHost
            #ntlmDomain: someDomain
            preemptive: false # or true
```

```yaml
repository:
  pruneBlobStores: true # True to delete blob stores not present in this configuration file
  blobStores: # List of blob stores to create
    - name: maven
      type: File
      attributes:
        file:
          path: maven
        blobStoreQuotaConfig:
          quotaLimitBytes: 10240000000
          quotaType: spaceUsedQuota
    - name: npm
      type: File
      attributes:
        file:
          path: npm
        blobStoreQuotaConfig:
          quotaLimitBytes: 10240000000
          quotaType: spaceUsedQuota
    - name: docker
      type: File
      attributes:
        file:
          path: docker
        blobStoreQuotaConfig:
          quotaLimitBytes: 10240000000
          quotaType: spaceUsedQuota
    - name: main
      type: S3
      attributes:
        s3:
          bucket: 'some-bucket' # (mandatory) AWS bucket
          prefix: '/nexus/'    # (optional) prefix for structure in bucket
          # Nexus uses default S3 provider chain so options are:
          # 1. Usual AWS_PROFILE, AWS_ACCESS_KEY etc. environment variables
          # 2. IAM Instance Profile with appropriate IAM role and policy (see Nexus docs)
          # 3. Explicit credentials (below)
          accessKeyId: 'some_key' # (optional) AWS access key Id
          secretAccessKey: 'some_secret_key'  # (optional) AWS secret access key
          sessionToken: 'some_session_token'  # (optional) AWS session token
          assumeRole: 'power-users' # (optional) custom IAM role to assume
          region: 'eu-west-1'   # (optional) AWS region
          endpoint: 'https://s3.custom-endpoint.somewhere/'
          expiration: '3'  # (optional) days, default=3
          signertype: none # (optional) 'one of none(default)|S3SignerType|AWSS3V4SignerType'
          forcepathstyle: false # (optional) 'false(default)|true'
          encryption_type: DEFAULT # (optional) 'one of DEFAULT(default)|s3ManagedEncryption|kmsManagedEncryption'
          encryption_key: 'aws/s3' # (required kmsManagedEncryption only) AWS KMS Key Id or KMS Key Alias
  pruneCleanupPolicies: true # True to delete cleanup policies not present in this configuration file
  cleanupPolicies:
    - name: cleanup-maven-proxy
      format: maven2
      notes: ''
      criteria:
        lastDownloaded: 864000
        lastBlobUpdated: 864000
    - name: cleanup-npm-proxy
      format: npm
      notes: ''
      criteria:
        lastDownloaded: 864000
    - name: cleanup-docker-proxy
      format: docker
      notes: ''
      criteria:
        lastDownloaded: 864000
  pruneRepositories: true # True to delete repositories not present in this configuration file
  repositories:
    - name: npm-proxy
      online: true
      recipeName: npm-proxy
      attributes:
        proxy:
          remoteUrl: https://registry.npmjs.org
          contentMaxAge: -1.0
          metadataMaxAge: 1440.0
        httpclient:
          blocked: false
          autoBlock: true
          connection:
            useTrustStore: false
        storage:
          blobStoreName: npm
          strictContentTypeValidation: true
        routingRules:
          routingRuleId: null
        negativeCache:
          enabled: true
          timeToLive: 1440.0
        cleanup:
          policyName: cleanup-npm-proxy
    - name: npm-hosted
      online: true
      recipeName: npm-hosted
      attributes:
        storage:
          blobStoreName: npm
          strictContentTypeValidation: true
          writePolicy: ALLOW_ONCE
        cleanup:
          policyName: None
    - name: npm
      online: true
      recipeName: npm-group
      attributes:
        storage:
          blobStoreName: npm
          strictContentTypeValidation: true
        group:
          memberNames:
           - "npm-proxy"
           - "npm-hosted"
    - name: maven-snapshots
      online: true
      recipeName: maven2-hosted
      attributes:
        maven:
          versionPolicy: SNAPSHOT
          layoutPolicy: STRICT
        storage:
          writePolicy: ALLOW
          strictContentTypeValidation: false
          blobStoreName: maven
    - name: maven-central
      online: true
      recipeName: maven2-proxy
      attributes:
        proxy:
          contentMaxAge: -1
          remoteUrl: https://repo1.maven.org/maven2/
          metadataMaxAge: 1440
        negativeCache:
          timeToLive: 1440
          enabled: true
        storage:
          strictContentTypeValidation: false
          blobStoreName: maven
        httpClient:
          connection:
            blocked: false
            autoBlock: true
        maven:
          versionPolicy: RELEASE
          layoutPolicy: PERMISSIVE
        cleanupPolicy:
          name: cleanup-maven-proxy
        httpclient:
        maven-indexer:
    - name: maven-tudelft
      online: true
      recipeName: maven2-proxy
      attributes:
        proxy:
          contentMaxAge: -1
          remoteUrl: https://simulation.tudelft.nl/maven/
          metadataMaxAge: 1440
        negativeCache:
          timeToLive: 1440
          enabled: true
        storage:
          strictContentTypeValidation: false
          blobStoreName: maven
        httpClient:
          connection:
            blocked: false
            autoBlock: true
        maven:
          versionPolicy: RELEASE
          layoutPolicy: PERMISSIVE
        cleanupPolicy:
          name: cleanup-maven-proxy
        httpclient:
        maven-indexer:
    - name: maven-public
      online: true
      recipeName: maven2-group
      attributes:
        maven:
          versionPolicy: MIXED
        group:
          memberNames:
           - "maven-central"
           - "maven-snapshots"
           - "maven-tudelft"
        storage:
          blobStoreName: maven
    - name: docker-hosted
      online: true
      recipeName: docker-hosted
      attributes:
        docker:
          forceBasicAuth: true
          v1Enabled: false
        storage:
          blobStoreName: docker
          strictContentTypeValidation: true
          writePolicy: ALLOW_ONCE
        cleanup:
          policyName: None
    - name: docker-proxy
      online: true
      recipeName: docker-proxy
      attributes:
        docker:
          forceBasicAuth: true
          v1Enabled: false
        proxy:
          remoteUrl: https://registry-1.docker.io
          contentMaxAge: -1.0
          metadataMaxAge: 1440.0
        dockerProxy:
          indexType: REGISTRY
        httpclient:
          blocked: false
          autoBlock: true
          connection:
            useTrustStore: false
        storage:
          blobStoreName: docker
          strictContentTypeValidation: true
        routingRules:
          routingRuleId: null
        negativeCache:
          enabled: true
          timeToLive: 1440.0
        cleanup:
          policyName: cleanup-docker-proxy
    - name: docker
      online: true
      recipeName: docker-group
      attributes:
        docker:
          forceBasicAuth: true
          v1Enabled: false
        storage:
          blobStoreName: docker
          strictContentTypeValidation: true
        group:
          memberNames:
            - "docker-hosted"
            - "docker-proxy"
```

Additional examples including apt, raw and yum are in the file `default-nexus.yml`
