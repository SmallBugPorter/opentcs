// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.opentcs.access.rmi.KernelServicePortalBuilder;
import org.opentcs.common.GuestUserCredentials;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;

/** Live MQTT + Kernel consistency check. Optional 'pause' tests a single virtual car. */
class SortingMqttIntegrationTest {
  public static void main(String[] args) throws Exception {
    var states=new ConcurrentHashMap<String,State>();
    var p=SortingMqttFleet.properties(SortingMqttFleet.CONFIG);String prefix="commadapter.vehicle.vda5050.mqtt.";
    var mqtt=new MqttClient((Boolean.parseBoolean(p.getProperty(prefix+"connectionEncrypted","false"))?"ssl":"tcp")
        +"://"+p.getProperty(prefix+"brokerHost")+":"+p.getProperty(prefix+"brokerPort"),"sorting-verification-"+ProcessHandle.current().pid(),new MemoryPersistence());
    var opt=new MqttConnectOptions();opt.setConnectionTimeout(5);
    if(!p.getProperty(prefix+"username", "").isEmpty()) {opt.setUserName(p.getProperty(prefix+"username"));opt.setPassword(p.getProperty(prefix+"password", "").toCharArray());}
    mqtt.setCallback(new MqttCallback() {
      public void connectionLost(Throwable cause) {}
      public void deliveryComplete(IMqttDeliveryToken token) {}
      public void messageArrived(String topic,MqttMessage message) {
        var state=SortingMqttVehicle.JSON.fromJson(new String(message.getPayload(),StandardCharsets.UTF_8),State.class);
        states.put(state.getSerialNumber(),state);
      }
    });
    var portal=new KernelServicePortalBuilder(GuestUserCredentials.USER,GuestUserCredentials.PASSWORD).build();
    try {
      mqtt.connect(opt);mqtt.subscribe("uagv/v2/SortingDemo/+/state",1);portal.login("localhost",1099);
      long deadline=System.currentTimeMillis()+15000;
      while(states.size()<SortingSimulation.VEHICLES && System.currentTimeMillis()<deadline)Thread.sleep(100);
      SortingMqttTest.require(states.size()==SortingSimulation.VEHICLES,"Expected live MQTT states from all " + SortingSimulation.VEHICLES + " vehicles, got "+states.size());
      for(var state:states.values()) {
        SortingMqttTest.require(state.getVersion().equals("2.0.0"),"Message version");
        var v=portal.getVehicleService().fetch(Vehicle.class,state.getSerialNumber()).orElseThrow();
        SortingMqttTest.require(v.getState()!=Vehicle.State.UNKNOWN&&v.getState()!=Vehicle.State.UNAVAILABLE&&v.getState()!=Vehicle.State.ERROR&&v.getCurrentPosition()!=null,"Kernel received state for "+v.getName());
        var model=portal.getVehicleService().fetchProcessModel(v.getReference());
        SortingMqttTest.require(model instanceof org.opentcs.commadapter.vehicle.vda5050.v2_0.controlcenter.ProcessModelImplTO,"VDA5050 2.0 adapter for "+v.getName());
        var mqttModel=(org.opentcs.commadapter.vehicle.vda5050.v2_0.controlcenter.ProcessModelImplTO)model;
        SortingMqttTest.require(mqttModel.isBrokerConnected() && mqttModel.getCurrentConnection()!=null
            && mqttModel.getCurrentConnection().getConnectionState()==org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.ConnectionState.ONLINE,
            "Broker and vehicle ONLINE: "+v.getName());
      }
      System.out.println("PASS: " + states.size() + " live MQTT state topics; all VDA5050 adapters have known Kernel positions/states");
      if(args.length>0 && args[0].equals("pause")) {
        String name=states.values().stream().filter(s->Boolean.TRUE.equals(s.isDriving())).findFirst().orElseThrow().getSerialNumber();
        var v=portal.getVehicleService().fetch(Vehicle.class,name).orElseThrow();
        try {
          portal.getVehicleService().updateVehiclePaused(v.getReference(),true);
          deadline=System.currentTimeMillis()+5000;
          while(!Boolean.TRUE.equals(states.get(name).isPaused()) && System.currentTimeMillis()<deadline)Thread.sleep(100);
          var before=states.get(name);
          SortingMqttTest.require(Boolean.TRUE.equals(before.isPaused()),"Pause acknowledged through MQTT");
          Thread.sleep(2200);var after=states.get(name);
          SortingMqttTest.require(before.getLastNodeId().equals(after.getLastNodeId())
              && before.getAgvPosition().getX().equals(after.getAgvPosition().getX())
              && before.getAgvPosition().getY().equals(after.getAgvPosition().getY()),"Paused car must not advance");
          System.out.println("PASS: "+name+" pause acknowledged and position frozen through MQTT");
        } finally {portal.getVehicleService().updateVehiclePaused(v.getReference(),false);}
      }
      var completed=new HashSet<String>();
      for(var order:portal.getTransportOrderService().fetch(TransportOrder.class))
        if(order.getState()==TransportOrder.State.FINISHED && "DELIVERY".equals(order.getProperty("sorting:stage")))
          completed.add(order.getProcessingVehicle().getName());
      System.out.println("Vehicles with confirmed DELIVERY FINISHED="+completed.size()+"/"+SortingSimulation.VEHICLES+" "+new TreeSet<>(completed));
    } finally {portal.logout();if(mqtt.isConnected())mqtt.disconnect();mqtt.close();}
  }
}
