package com.example.subtele;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.*;
import java.util.regex.*;

import com.example.subtele.namespaces.*;
import java.util.Properties;
import com.tailf.conf.*;
import com.tailf.navu.*;
import com.tailf.ncs.ns.Ncs;
import com.tailf.dp.*;
import com.tailf.dp.annotations.*;
import com.tailf.dp.proto.*;
import com.tailf.dp.services.*;
import com.tailf.ncs.template.Template;
import com.tailf.ncs.template.TemplateVariables;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.x5.template.Theme;
import com.x5.template.Chunk;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.DockerCmdExecFactory;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.jaxrs.DockerCmdExecFactoryImpl;

public class subteleRFS {


  /**
  * Create callback method.
  * This method is called when a service instance committed due to a create
  * or update event.
  *
  * This method returns a opaque as a Properties object that can be null.
  * If not null it is stored persistently by Ncs.
  * This object is then delivered as argument to new calls of the create
  * method for this service (fastmap algorithm).
  * This way the user can store and later modify persistent data outside
  * the service model that might be needed.
  *
  * @param context - The current ServiceContext object
  * @param service - The NavuNode references the service node.
  * @param ncsRoot - This NavuNode references the ncs root.
  * @param opaque  - Parameter contains a Properties object.
  *                  This object may be used to transfer
  *                  additional information between consecutive
  *                  calls to the create callback.  It is always
  *                  null in the first call. I.e. when the service
  *                  is first created.
  * @return Properties the returning opaque instance
  * @throws ConfException
  */

  @ServiceCallback(servicePoint="subtele-servicepoint",
      callType=ServiceCBType.CREATE)
  public Properties create( ServiceContext context,
                            NavuNode service,
                            NavuNode ncsRoot,
                            Properties opaque)
                            throws ConfException {

    try {
      // check if it is reasonable to assume that devices
      // initially has been sync-from:ed
      NavuList managedDevices = ncsRoot.
      container("devices").list("device");
      for (NavuContainer device : managedDevices) {
        if (device.list("capability").isEmpty()) {
          String mess = "Device %1$s has no known capabilities, " +
          "has sync-from been performed?";
          String key = device.getKey().elementAt(0).toString();
          throw new DpCallbackException(String.format(mess, key));
        }
      }
    } catch (DpCallbackException e) {
      throw (DpCallbackException) e;
    } catch (Exception e) {
      throw new DpCallbackException("Not able to check devices", e);
    }

    Template subteleTemplate = new Template(context, "subtele");

    //subscription parametres
    String subscriptionID = service.leaf("subscription-id").valueAsString();
    Boolean subscriptionEnable = service.leaf("enable").exists();
    String destinationDSCP = service.leaf("source-qos-marking").valueAsString();

    //sources deviceNames[]
    String sourceID = service.container("source-group").leaf("source-id").valueAsString();
    NavuLeaf sourceDevices = service.container("source-group").leaf("device");
    String[] deviceNames = sourceDevices.valueAsString().split(" ");

    //sensorpaths sensorPaths[]
    String sensorID = service.container("sensor-group").leaf("sensor-id").valueAsString();
    String sensorSamItv = service.container("sensor-group").leaf("sample-interval").valueAsString();
    String sensorHbItv = service.container("sensor-group").leaf("heartbeat-interval").valueAsString();
    Boolean  sensorSupRed = service.container("sensor-group").leaf("supress-redundant").exists();
    NavuLeaf sensorPaths = service.container("sensor-group").leaf("sensor-path");

    //destinations desContents[]}
    String destinationID = service.container("destination-group").leaf("destination-id").valueAsString();
    String destinationEncode = service.container("destination-group").leaf("encoding").valueAsString();
    String destinationProto = service.container("destination-group").leaf("protocol").valueAsString();
    String destination = service.container("destination-group").leaf("destination").valueAsString();
    String[] desArray = parseDes(destination);

    //telemetry visualization enable
    Boolean enableVisual = service.leaf("tele_visual").exists();

    //device configuration
    for( String device : deviceNames){
      try{
        TemplateVariables myVars = new TemplateVariables();

        myVars.putQuoted("DEVICE",device);
        myVars.putQuoted("ENABLE",subscriptionEnable.toString());

        //subscription parametres
        myVars.putQuoted("SUBSCRIPTION_ID",subscriptionID);
        myVars.putQuoted("SENSOR_GROUP_ID",sensorID);
        myVars.putQuoted("SENSOR_SAMPLE_INTERVAL",sensorSamItv);
        myVars.putQuoted("SENSOR_HEARTBEAT_INTERVAL",sensorHbItv);
        myVars.putQuoted("SENSOR_SUPRESS_REDUNDANT",sensorSupRed.toString());
        myVars.putQuoted("DESTINATION_ID",destinationID);
        myVars.putQuoted("SOURCE_QOS_MARKING",destinationDSCP);

        //destinations parameters
        myVars.putQuoted("DESTINATION_ADD_FAM",desArray[0]);
        myVars.putQuoted("DESTINATION_ADD",desArray[1]);
        myVars.putQuoted("DESTINATION_PORT",desArray[2]);
        myVars.putQuoted("DESTINATION_ENCODE",destinationEncode);
        myVars.putQuoted("DESTINATION_PROTO",destinationProto);

        //sensorpaths parameters
        for(String path : sensorPaths.valueAsString().split(" ")){
          myVars.putQuoted("SENSOR_PATH",path);
          subteleTemplate.apply(service,myVars);
        }

      } catch (Exception e) {
        throw new DpCallbackException(e.getMessage(), e);
      }
    }

    //launch elk docker containers
    if (enableVisual){
      try{
        launchELK(desArray);
      } catch(IOException e){
        e.printStackTrace();
      }
    }

    return opaque;
  }

  public String[] parseDes(String address_port){
    String[] desArray = new String[3];

    final String IPV4 = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?):\\d*$";
    final String IPV6 = "^([\\da-fA-F]{1,4}:){7}[\\da-fA-F]{1,4}:\\d*$";

    Pattern p4 = Pattern.compile(IPV4);
    Pattern p6 = Pattern.compile(IPV6);
    Matcher m = p4.matcher(address_port); // get a matcher object

    if(m.find()){
      desArray[0] = "ipv4";
      desArray[1] = m.group().split(":")[0];
      desArray[2] = m.group().split(":")[1];
    }else{
      m = p6.matcher(address_port);
      if(m.find()){
        desArray[0] = "ipv6";
        desArray[2] = m.group().split(":")[8];
        desArray[1] = m.group().substring(0, m.group().length()-desArray[2].length()-1);;
      }
    }

    return desArray;
  }


  public void launchELK(String[] desArray) throws DockerException, IOException{

    String host = desArray[1];
    String port = desArray[2];

    //create docker client
    DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
      .withDockerHost("unix:///var/run/docker.sock")
      .withApiVersion("1.23")
      .build();

    // using jaxrs/jersey implementation here (netty impl is also available)
    DockerCmdExecFactory dockerCmdExecFactory = new DockerCmdExecFactoryImpl()
      .withReadTimeout(1000)
      .withConnectTimeout(1000)
      .withMaxTotalConnections(100)
      .withMaxPerRouteConnections(10);

    DockerClient dockerClient = DockerClientBuilder.getInstance(config)
      .withDockerCmdExecFactory(dockerCmdExecFactory)
      .build();

    // create volume in containers
    Volume volume_elasticsearch = new Volume("/usr/share/elasticsearch/data");
    Volume volume_logstash = new Volume("/opt/logstash/data");
    Volume volume_kibana = new Volume("/opt/kibana/data");

    // create local data volume on elk server
    new File("/volume/elasticsearch/").mkdirs();
    new File("/volume/logstash/").mkdirs();
    new File("/volume/kibana/").mkdirs();

    // ********* create elk configuration files
    Theme theme = new Theme();
    Chunk chunk_kibana = theme.makeChunk("kibana_template","yml");
    Chunk chunk_logstash = theme.makeChunk("logstash_template","conf");

    // replace static values below with user input
    chunk_kibana.set("host", host);
    chunk_logstash.set("host", host);
    chunk_logstash.set("port", port);

    File file_logstash = new File("/volume/logstash/ls_telemetry.conf");
    FileWriter out_logstash = new FileWriter(file_logstash);
    chunk_logstash.render(out_logstash);
    out_logstash.flush();
    out_logstash.close();

    File file_kibana = new File("/volume/kibana/kibana.yml");
    FileWriter out_kibana = new FileWriter(file_kibana);
    chunk_kibana.render(out_kibana);
    out_kibana.flush();
    out_kibana.close();
    // ***********

    // // create elasticsearch image
    // File elasticsearchDir = new File("./elasticsearch");
    // String elasticsearchID = dockerClient.buildImageCmd(elasticsearchDir)
    //     .withNoCache(true)
    //     .withTag("elk_elasticsearch")
    //     .exec(new BuildImageResultCallback())
    //     .awaitImageId();
    // Info info = dockerClient.infoCmd().exec();
    // System.out.println(info.toString());
    
    // // create logstash image
    // File logstashDir = new File("./logstash");
    // String logstashID = dockerClient.buildImageCmd(logstashDir)
    //     .withNoCache(true)
    //     .withTag("elk_logstash")
    //     .exec(new BuildImageResultCallback())
    //     .awaitImageId();
    // info = dockerClient.infoCmd().exec();
    // System.out.println(info.toString());
    
    // // create kibana image
    // File kibanaDir = new File("./kibana");
    // String KibanaID = dockerClient.buildImageCmd(kibanaDir)
    //     .withNoCache(true)
    //     .withTag("elk_kibana")
    //     .exec(new BuildImageResultCallback())
    //     .awaitImageId();
    // info = dockerClient.infoCmd().exec();
    // System.out.println(info.toString());

    // list all images
    List<Image> dockerList =  dockerClient.listImagesCmd().exec();
    System.out.println("Search returned" + dockerList.toString());

    //create elasticsearch container
    ExposedPort tcp9200 = ExposedPort.tcp(9200);
    ExposedPort tcp9300 = ExposedPort.tcp(9300);
    Ports portBindings = new Ports();
    portBindings.bind(tcp9200, Ports.Binding.bindPort(9200));
    portBindings.bind(tcp9300, Ports.Binding.bindPort(9300));

    CreateContainerResponse container_elasticsearch = dockerClient.createContainerCmd("elk_elasticsearch")
        .withVolumes(volume_elasticsearch)
        .withName("stack_elk_elasticsearch")
        .withCmd("elasticsearch",
            "-Des.insecure.allow.root=true",
            new String(String.format("----network.host= %s", host)))
        .withPortBindings(portBindings)
        .withBinds(new Bind("/volume/elasticsearch", volume_elasticsearch))
        .exec();

    // create logstash container
    CreateContainerResponse container_logstash = dockerClient.createContainerCmd("elk_logstash")
        .withVolumes(volume_logstash)
        .withName("stack_elk_logstash")
        .withBinds(new Bind("/volume/logstash", volume_logstash))
        .exec();

    // create kibana container
    ExposedPort tcp5601 = ExposedPort.tcp(5601);
    Ports portBinding = new Ports();
    portBinding.bind(tcp5601, Ports.Binding.bindPort(5601));

    CreateContainerResponse container_kibana = dockerClient.createContainerCmd("elk_kibana")
        .withVolumes(volume_kibana)
        .withName("stack_elk_kibana")
        .withBinds(new Bind("/volume/kibana", volume_kibana))
        .withPortBindings(portBinding)
        .exec();

    // launch elk containers
    dockerClient.startContainerCmd(container_elasticsearch.getId()).exec();
    dockerClient.startContainerCmd(container_logstash.getId()).exec();
    dockerClient.startContainerCmd(container_kibana.getId()).exec();

    return;
  }

  /**
  * Init method for selftest action
  */
  @ActionCallback(callPoint="subtele-self-test", callType=ActionCBType.INIT)
  public void init(DpActionTrans trans) throws DpCallbackException {
  }

  /**
  * Selftest action implementation for service
  */
  @ActionCallback(callPoint="subtele-self-test", callType=ActionCBType.ACTION)
  public ConfXMLParam[] selftest(DpActionTrans trans, ConfTag name,
                                 ConfObject[] kp, ConfXMLParam[] params)
  throws DpCallbackException {
    try {
      // Refer to the service yang model prefix
      String nsPrefix = "subtele";
      // Get the service instance key
      String str = ((ConfKey)kp[0]).toString();

      return new ConfXMLParam[] {
        new ConfXMLParamValue(nsPrefix, "success", new ConfBool(true)),
        new ConfXMLParamValue(nsPrefix, "message", new ConfBuf(str))};

      } catch (Exception e) {
        throw new DpCallbackException("self-test failed", e);
      }
  }
}

