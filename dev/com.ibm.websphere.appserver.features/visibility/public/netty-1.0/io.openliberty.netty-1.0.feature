-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=io.openliberty.netty-1.0
visibility=public
singleton=true
IBM-App-ForceRestart: install, \
  uninstall
IBM-ShortName: netty-1.0
Subsystem-Name: Netty Transport 1.0
-features=\
  com.ibm.websphere.appserver.channelfw-1.0, \
  io.openliberty.io.netty, \
  io.openliberty.io.netty.ssl
-bundles=\
  io.openliberty.netty.channel
kind=noship
edition=core
WLP-Activation-Type: parallel
