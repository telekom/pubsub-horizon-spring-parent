<!--
Copyright 2024 Deutsche Telekom IT GmbH

SPDX-License-Identifier: Apache-2.0
-->

<p align="center">
  <h1 align="center">Horizon Spring Parent</h1>
</p>

<p align="center">
  Parent Spring library required to build Horizon components.
</p>

<p align="center">
  <a href="#building-the-library">Building the library</a> •
  <a href="#using-the-library">Using the library</a> 
</p>

## Overview

This repository provides spring starter library that provides common functionality and models used by all [Horizon](https://github.com/telekom/pubsub-horizon) components. 
Basically it is a spring-boot-starter which provides some autoconfigurations for common tasks across the spring-boot components.

* HorizonAutoConfiguration:
  * HorizonMetricsHelperAutoConfiguration
  * HorizonTracerAutoConfiguration
  * CacheAutoConfiguration
  * MongoAutoConfiguration
  * KafkaAutoConfiguration
* KubernetesClientAutoConfiguration

Additionally, the horizon-spring-parent contains a core project with different classes and interfaces as well as constants 
for topics in the area of caching (hazelcast), kafka, kubernetes, metrics, tracing and logging.

## Building the library

This project uses gradle as build tool.

To build the project, run the following command:  
```
./gradlew clean build
```

## Using the library

Add the following to your `build.gradle`:  
```
def horizonParentVersion = '1.0.0'

dependencies {
  implementation "de.telekom.eni:horizon-spring-boot-starter:${horizonParentVersion}"
}
```

This library will provide an autoconfigured `HorizonTracer` bean that you can use in order to do tracing in an Horizon component.

### Configuration parameters

```yaml
kubernetes:
  enabled: true
  rover:
    token: # no default
  kubeConfigPath:
  informer:
    resyncperiod:
      ms: 600000
    namespace: # no default
    pods:
      namespace: # no default
      appname: # no default
  requestTimeoutMs: 120000
  connectionTimeoutMs: 120000

horizon:
  mongo:
    enabled: false
    clientId: unset
    url: mongodb://root:ineedcoffee@localhost:27017
    database: horizon
    writeConcern: WriteConcern.W1
    maxConnections: 100
    maxRetries: 10
    retryDelay: 100
    maxTimeout: 30000
  kafka:
    bootstrapServers: localhost:9092
    disableGroupId: false
    groupId: default-group
    partitionCount: 10
    autoCreateTopics: false
    autoOffsetReset: latest
    isolationLevel: read_committed
    maxPollRecords: 500
    lingerMs: 0
    acks: 1
  cache:
    enabled: false
    name: cache
    kubernetesServiceDns: # no default
    deDuplication:
      enabled: false
      defaultCacheName: deDuplication
      ttlInSeconds: 0
      maxIdleInSeconds: 1800
    
pandora:
  tracing:
    name: unsetLocalServiceName
    debugEnabled: false
```

#### Special configurations

* If a `spring.profiles.active` is set with 'dev' or 'test', the kubernetes client will be configured using a configuration 
  file with the static path `KUBERNETES_DEV_CONFIG_FILE_PATH=kubernetes/config/config.laptop-dev-dev-system`
* Within tracing all horizon pubsub components appear under a common service name `pandora.tracing.name` in the tracing 
  system. This is useful to identify the service in the tracing system. The actual component name in the trace is also marked 
  in the span as tag `component`. All pubsub components use the value `horizon`.

## Contributing

We're committed to open source, so we welcome and encourage everyone to join its developer community and contribute, whether it's through code or feedback.  
By participating in this project, you agree to abide by its [Code of Conduct](./CODE_OF_CONDUCT.md) at all times.

## Code of Conduct

This project has adopted the [Contributor Covenant](https://www.contributor-covenant.org/) in version 2.1 as our code of conduct. Please see the details in our [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). All contributors must abide by the code of conduct.

By participating in this project, you agree to abide by its [Code of Conduct](./CODE_OF_CONDUCT.md) at all times.

## Licensing

This project follows the [REUSE standard for software licensing](https://reuse.software/).
Each file contains copyright and license information, and license texts can be found in the [./LICENSES](./LICENSES) folder. For more information visit https://reuse.software/.

### REUSE

For a comprehensive guide on how to use REUSE for licensing in this repository, visit https://telekom.github.io/reuse-template/.   
A brief summary follows below:

The [reuse tool](https://github.com/fsfe/reuse-tool) can be used to verify and establish compliance when new files are added. 

For more information on the reuse tool visit https://github.com/fsfe/reuse-tool.
