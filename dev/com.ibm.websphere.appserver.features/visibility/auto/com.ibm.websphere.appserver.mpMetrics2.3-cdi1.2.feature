-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=com.ibm.websphere.appserver.mpMetrics2.3-cdi1.2
visibility=private
singleton=true
IBM-Provision-Capability: \
  osgi.identity; filter:="(&(type=osgi.subsystem.feature)(osgi.identity=com.ibm.websphere.appserver.mpMetrics-2.3))", \
  osgi.identity; filter:="(&(type=osgi.subsystem.feature)(|(osgi.identity=com.ibm.websphere.appserver.cdi-1.2)(osgi.identity=com.ibm.websphere.appserver.cdi-2.0)))"
-bundles=com.ibm.ws.microprofile.metrics.cdi.2.3
IBM-Install-Policy: when-satisfied
kind=beta
edition=core