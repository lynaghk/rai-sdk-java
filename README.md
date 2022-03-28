![build](https://github.com/RelationalAI/rai-sdk-java/actions/workflows/maven-build.yaml/badge.svg)
![publish](https://github.com/RelationalAI/rai-sdk-java/actions/workflows/maven-publish.yaml/badge.svg)
![javadoc](https://github.com/RelationalAI/rai-sdk-java/actions/workflows/maven-javadoc.yaml/badge.svg)

# The RelationalAI Software Development Kit for Java

The RelationalAI (RAI) SDK for Java enables developers to access the RAI REST
APIs from Java.

* You can find RelationalAI Java SDK documentation at <https://docs.relational.ai/rkgms/sdk/java-sdk>
* You can find RelationalAI product documentation at <https://docs.relational.ai>
* You can learn more about RelationalAI at <https://relational.ai>

## Getting started

### Requirements

* Java 11.0.10+
* Apache Maven

### Dependencies

The SDK has a single runtime dependency (jsoniter), a dependency for
running the SDK examples (commons-cli) and several additional dependencies
for running the unit and integration tests.

### Building the SDK

The SDK build lifecycle is managed using the standard `mvn` lifecycle commands.

**Compile the SDK**

    mvn compile

**Run the tests**

    mvn test
    
Note, the test are run against the account configured in your SDK config file.

**Compile, package, run tests and install the SDK**

    mvn install

Note that `mvn install` is required to build and run the examples.

**Compile, package and install without running tests**

    mvn install -DskipTests

**Remove all build output files from the repo**

    mvn clean

### Create a configuration file

In order to run the examples you will need to create an SDK config file.
The default location for the file is `$HOME/.rai/config` and the file should
include the following:

Sample configuration using OAuth client credentials:

    [default]
    host = azure.relationalai.com
    port = <api-port>      # optional, default: 443
    scheme = <scheme>      # optional, default: https
    client_id = <your client_id>
    client_secret = <your client secret>
    client_credentials_url = <account login URL>  # optional
    # default: https://login.relationalai.com/oauth/token

Client credentials can be created using the RAI console at
https://console.relationalai.com/login

You can copy `config.spec` from the root of this repo and modify as needed.

## Examples

The SDK contain examples for every API, and various other SDK features. These
are located in the examples project under `./rai-sdk-examples`.

The examples are packaged into a single JAR file, and can be run individually
from the command line using maven.

    mvn exec:java -f rai-sdk-examples/pom.xml -Dexec.mainClass=com.relationalai.examples.Runner -Dexec.args="<className> [options]"

Eg, to run the `GetDatabase` example from the root of the repo.

    mvn exec:java -f rai-sdk-examples/pom.xml -Dexec.mainClass=com.relationalai.examples.Runner -Dexec.args="GetDatabase sdk-test"

There is also a bash script in `./rai-sdk-examples` that can be used to run
individual examples, eg:

    cd ./rai-sdk/examples
    ./run GetDatabase sdk-test

## Javadocs

Javadocs for `rai-sdk` are available [here](https://musical-winner-94955c55.pages.github.io/com/relationalai/package-summary.html).

## Support

You can reach the RAI developer support team at `support@relational.ai`

## Contributing

We value feedback and contributions from our developer community. Feel free
to submit an issue or a PR here.

## License

The RelationalAI Software Development Kit for Java is licensed under the
Apache License 2.0. See:
https://github.com/RelationalAI/rai-sdk-java/blob/master/LICENSE
