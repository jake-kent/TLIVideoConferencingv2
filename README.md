TLI Video Conferencing Media Server and Corresponding Front End
================

TLI Video Conferencing Media Server

Steps to Run:
----------------
- Start an Ubuntu 16.04 instance
- Follow steps at [Kurento Installation Guide](http://doc-kurento.readthedocs.io/en/stable/installation_guide.html) to start Kurento Media Server
- sudo apt-get install git maven
- sudo apt-get install openjdk-8-jdk openjdk-8-doc openjdk-8-jre
- sudo git clone https://github.com/jake-kent/TLIVideoConferencingv2.git
- within the kurento-group-call subfolder of the repository call: sudo mvn compile exec:java
