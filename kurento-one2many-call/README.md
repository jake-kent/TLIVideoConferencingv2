[TLI](http://www.tli.com.tw/) Teacher to Student(s) Video Conferencing w/ Server-side Recording
=====================

By: [Jake Kent](https://github.com/jake-kent)

Running this tutorial
---------------------

Install Docker

To Start at new Kurento Media Server on Docker:
- docker run --name kms -p 8888:8888 -p 8443:32780 -d kurento/kurento-media-server:6.6.0 --cap-add=SETPCAP

To Open the Server Shell:
- bash -c "clear && docker exec -it kms sh"

Within the resulting shell:
- sudo service kurento-media-server-6.0 start
- sudo apt-get update
- sudo apt-get install git maven
- sudo apt-get install openjdk-7-jdk openjdk-7-doc openjdk-7-jre-lib

- Clone this repository into the location of your choice and run:

- mvn compile exec:java

- A port for the app can be specified in pom.xml
