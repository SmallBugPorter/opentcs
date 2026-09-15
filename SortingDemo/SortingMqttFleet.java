// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.*;

/** Launches one MQTT client per Sorting vehicle, using the project's VDA5050 messages. */
class SortingMqttFleet {
  static final Path CONFIG = Path.of("build/install/opentcs/opentcs-kernel/config/opentcs-kernel.properties");
  static final Path RUNTIME = Path.of("SortingDemo/runtime/mqtt-vehicles");
  static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
  static final List<MqttClient> CLIENTS = new ArrayList<>();
  static final List<SortingMqttVehicle> VEHICLES = new ArrayList<>();
  static volatile boolean stopping;

  static Properties properties(Path path) throws Exception {
    var result=new Properties(); try(var in=Files.newInputStream(path)) {result.load(in);} return result;
  }
  static Map<String,String> attributes(Element element) {
    var result=new HashMap<String,String>();
    for (Node child=element.getFirstChild();child!=null;child=child.getNextSibling())
      if(child instanceof Element e && e.getTagName().equals("property")) result.put(e.getAttribute("name"),e.getAttribute("value"));
    return result;
  }
  static void connect(SortingMqttVehicle vehicle, Properties config) throws Exception {
    String prefix="commadapter.vehicle.vda5050.mqtt.";
    String id="sorting-sim-"+vehicle.serial;
    if(id.equals(config.getProperty(prefix+"clientId"))) throw new IllegalArgumentException("Duplicate Kernel/simulator client ID");
    var client=new MqttClient((Boolean.parseBoolean(config.getProperty(prefix+"connectionEncrypted","false"))?"ssl":"tcp")
        +"://"+config.getProperty(prefix+"brokerHost","127.0.0.1")+":"+config.getProperty(prefix+"brokerPort","1883"),id,new MemoryPersistence());
    client.setTimeToWait(5000);
    CLIENTS.add(client);
    client.setCallback(new MqttCallbackExtended() {
      public void connectComplete(boolean reconnect,String uri) {
        EXECUTOR.execute(() -> {
          try {
            client.subscribe(new String[]{vehicle.topic+"/order",vehicle.topic+"/instantActions"},new int[]{1,1});
            vehicle.connection(ConnectionState.ONLINE); vehicle.publish();
            System.out.println("ONLINE "+vehicle.name+" clientId="+id+" topic="+vehicle.topic);
          } catch(Exception e) { fail(e); }
        });
      }
      public void connectionLost(Throwable cause) {System.err.println("OFFLINE "+vehicle.name+": "+cause.getMessage());}
      public void messageArrived(String topic,MqttMessage message) {
        EXECUTOR.execute(() -> {
          try {
            if (message.isRetained()) throw new IllegalArgumentException("Refusing retained vehicle command");
            vehicle.receive(topic.substring(topic.lastIndexOf('/')+1),new String(message.getPayload(),java.nio.charset.StandardCharsets.UTF_8));
            vehicle.save(RUNTIME.resolve(vehicle.name+".properties"),fingerprint);
          } catch(Exception e) { fail(e); }
        });
      }
      public void deliveryComplete(IMqttDeliveryToken token) { }
    });
    var options=new MqttConnectOptions(); options.setAutomaticReconnect(true); options.setCleanSession(true);
    options.setConnectionTimeout(5); options.setKeepAliveInterval(10);
    String username=config.getProperty(prefix+"username", "");
    if(!username.isEmpty()) {options.setUserName(username);options.setPassword(config.getProperty(prefix+"password", "").toCharArray());}
    options.setWill(vehicle.topic+"/connection",SortingMqttVehicle.JSON.toJson(new Connection(0L,java.time.Instant.now(),"2.0.0",
        vehicle.manufacturer,vehicle.serial,ConnectionState.CONNECTIONBROKEN)).getBytes(java.nio.charset.StandardCharsets.UTF_8),1,true);
    client.connect(options);
  }
  static String fingerprint;
  static void fail(Exception error) {
    error.printStackTrace();
    // Stop the process on protocol/state errors instead of silently leaving a dead simulation loop.
    new Thread(() -> System.exit(1)).start();
  }
  public static void main(String[] args) {
    try { start(); } catch (Exception error) { error.printStackTrace(); System.exit(1); }
  }
  static void start() throws Exception {
    fingerprint=SortingSimulation.fingerprint();
    Properties config=new Properties();
    Path defaults=CONFIG.resolveSibling("opentcs-kernel-defaults-baseline.properties");
    if(Files.exists(defaults)) config.putAll(properties(defaults));
    Path custom=CONFIG.resolveSibling("opentcs-kernel-defaults-custom.properties");
    if(Files.exists(custom)) config.putAll(properties(custom));
    config.putAll(properties(CONFIG));
    if(!Arrays.asList(config.getProperty("commadapter.vehicle.vda5050.enabledVersions","").split("\\s*,\\s*")).contains("2.0"))
      throw new IllegalStateException("Enable VDA5050 2.0 in Kernel configuration first");
    var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    var doc=factory.newDocumentBuilder().parse(SortingSimulation.MODEL.toFile());
    var points=new HashMap<String,Element>(); var list=doc.getElementsByTagName("point");
    for(int i=0;i<list.getLength();i++) {var e=(Element)list.item(i);points.put(e.getAttribute("name"),e);}
    for (String key : List.of("motion.maxSpeed", "motion.operationSeconds", "battery.chargePercentPerSecond",
        "rotation.maxVelocityDegPerSec", "rotation.maxAccelerationDegPerSec2", "rotation.maxDecelerationDegPerSec2")) {
      double value = Double.parseDouble(SortingSimulation.SETTINGS.getProperty(key));
      if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid setting: " + key);
    }
    var vehicles=doc.getElementsByTagName("vehicle");
    if(vehicles.getLength()!=SortingSimulation.VEHICLES) throw new IllegalStateException("Expected " + SortingSimulation.VEHICLES + " vehicles");
    var identities=new HashSet<String>();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      stopping=true;
      try {
        EXECUTOR.submit(() -> {
          for(int i=0;i<VEHICLES.size();i++) try {
            var v=VEHICLES.get(i); v.publish(); v.save(RUNTIME.resolve(v.name+".properties"),fingerprint);
            if(CLIENTS.get(i).isConnected()) {v.connection(ConnectionState.OFFLINE);CLIENTS.get(i).disconnect();}
            CLIENTS.get(i).close();
          } catch(Exception e) {System.err.println("Shutdown: "+e);}
        }).get(15,TimeUnit.SECONDS);
      } catch(Exception e) {System.err.println("Shutdown: "+e);}
      EXECUTOR.shutdownNow();
    }));
    for(int i=0;i<vehicles.getLength();i++) {
      var e=(Element)vehicles.item(i);var props=attributes(e);String name=e.getAttribute("name");
      String serial=Objects.requireNonNull(props.get("vda5050:serialNumber"));
      String manufacturer=Objects.requireNonNull(props.get("vda5050:manufacturer"));
      String topic=Objects.requireNonNull(props.get("vda5050:interfaceName"))+"/v2/"+manufacturer+"/"+serial;
      if(!identities.add(topic)) throw new IllegalStateException("Duplicate vehicle topic");
      String initial=Objects.requireNonNull(props.get("sorting:initialPosition"));var point=Objects.requireNonNull(points.get(initial));
      final int index=i;
      var v=new SortingMqttVehicle(name,manufacturer,serial,topic,initial,
          Double.parseDouble(point.getAttribute("positionX"))/1000,Double.parseDouble(point.getAttribute("positionY"))/1000,
          SortingSimulation.SETTINGS,(suffix,payload) -> {
            if(index>=CLIENTS.size() || !CLIENTS.get(index).isConnected()) return;
            try {CLIENTS.get(index).publish(topic+"/"+suffix,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,suffix.equals("connection"));}
            catch(MqttException ex) {System.err.println("MQTT publish pending reconnect for "+name+": "+ex.getMessage());}
          });
      v.restore(RUNTIME.resolve(name+".properties"),fingerprint); VEHICLES.add(v); connect(v,config);
    }
    final int[] ticks={0};
    EXECUTOR.scheduleWithFixedDelay(() -> {
      if(stopping) return;
      try {
        for(int i=0;i<VEHICLES.size();i++) {
          var v=VEHICLES.get(i);
          if(CLIENTS.get(i).isConnected()) v.tick(.1); // Freeze on disconnect; preserve allocated base.
          if(ticks[0]%10==0) {v.publish();v.save(RUNTIME.resolve(v.name+".properties"),fingerprint);}
        }
        ticks[0]++;
      } catch(Exception e) {fail(e);}
    },100,100,TimeUnit.MILLISECONDS);
    System.out.println("Started " + VEHICLES.size() + " VDA5050 2.0 vehicle simulators; credentials inherited from Kernel config.");
  }
}
