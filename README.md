l# beacon-network-backend

###### Jakarta EE Platform 10
The implementation is developed and deployed on the [WildFly 30.0.0](http://wildfly.org/) server and is based on Jakarta RESTful Web Services 3.0 API ([JAX-RS 3.0](https://jakarta.ee/specifications/restful-ws/3.0/)).

###### Beacon v2 Java implementation
The implementation uses [Beacon v2 Java beacon-framework](https://github.com/elixir-europe/java-beacon-v2.api) model classes.

## Installation

### Docker Image (recommended)
This repository is configured to automatically generate [docker images](https://github.com/elixir-europe/beacon-network-backend/pkgs/container/beacon-network-backend) on release tags ('vX.Y.Z'). The image is based on official WildFly image that is extended with PostgreSQL driver and predeployed Beacon Network application.

To have an easy deployment, go to the [docker folder](./docker) and run [docker compose](https://docs.docker.com/compose/):

```
docker-compose up -d
```

### Manual installation

#### Apache Maven build system
The build process is based on [Apache Maven](https://maven.apache.org/).

Compiling:
```shell
git clone https://github.com/elixir-europe/beacon-network-backend.git
cd beacon-network-backend
mvn install
```
This must create `beacon-network-v2-x.x.x.war` (**W**eb application **AR**chive) application in the `/target` directory. Alternatively, you can find this file in the Barcelona Supercomputing Center's [maven repository](https://inb.bsc.es/maven/es/bsc/inb/ga4gh/beacon-network-v2/0.0.9/beacon-network-v2-0.0.9.war).

#### WilfFly server
WildFly is a free opensource JEE server and may be easy downloaded from it's website: (http://wildfly.org/).
The deployment is as simple:

```shell
# Copy .war file to wildfly
cp target/beacon-network-v2-x.x.x.war $WILDFLY_HOME/standalone/deployments/
# Run the application server
./$WILDFLY_HOME/bin/standalone.sh
```

### Configuration

There are three default configuration files in the `/BEACON-INF` directory:
* `configuration.json` - standard beacon configuration file: [beaconConfigurationResponse.json](https://github.com/ga4gh-beacon/beacon-v2/blob/main/framework/json/responses/beaconConfigurationResponse.json)
* `beacon-info.json` - standard beacon information file: [beaconInfoResponse.json](https://github.com/ga4gh-beacon/beacon-v2/blob/main/framework/json/responses/beaconInfoResponse.json)
* `beacon-network.json` - Json Array of backed Beacons' endpoints  

The example of the `beacon-network.json`:
```json
[
  "https://beacons.bsc.es/beacon/v2.0.0",
  "https://ega-archive.org/test-beacon-apis/cineca"
]
```
Note that the **W**eb application **AR**chive (WAR) is just a usual ZIP file so one can edit these configurations manually without the need to rebuild the application. The same with Docker, it is automatically updated with new beacons.

It is also possible to define external directory for the configuration.
```bash
export BEACON_NETWORK_CONFIG_DIR=/wildfly/BEACON-INF
```
When the `BEACON_NETWORK_CONFIG_DIR` is set, the aggregator monitors the `$BEACON_NETWORK_CONFIG_DIR/beacon-network.json` to dynamically update the configuration.  
It also looks (but not actively monitoring) the `$BEACON_NETWORK_CONFIG_DIR/beacon-info.json` so deployers may change the beacon identifier and other metatada.

### SQL Database

The Beacon Network Aggregator uses [Jakarta Persistence 3.1](https://jakarta.ee/specifications/persistence/3.1/) for logging.
The connection is defined in [persistence.xml](https://github.com/elixir-europe/beacon-network-backend/blob/master/src/main/resources/META-INF/persistence.xml).
Although the Aggregator may be used with any SQL database, it is configured to be used with [PostgreSQL](https://www.postgresql.org/) database.

The application provides simple SQL logging which level may be confirured via `BEACON_NETWORK_LOG_LEVEL` environment variable.  
The possible values are "**NONE**", "**METADATA**", "**REQUESTS**", "**RESPONSES**", "**ALL**"
- "**NONE**" : No logging at all.
- "**METADATA**" : Only backed beacons' metadata is logged (good for debugging).
- "**REQUESTS**" : Beacon Network Request quieries are logged. It also logs response codes (but not the data).
- "**RESPONSES**" : Logs all Requests with Responses as well as possible error messages.
- "**ALL**" : Maximum logging level. Currently same as "**RESPONSES**"

