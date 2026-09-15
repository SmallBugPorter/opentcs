// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.*;

class SortingMqttTest {
  static void require(boolean value, String why) {if(!value) throw new AssertionError(why);}
  static Node node(String id,long seq,boolean released,Action... actions) {
    return new Node(id,seq,released,List.of(actions)).setNodePosition(new NodePosition(seq/2.0,0.0,"Sorting"));
  }
  static Order order(long update,List<Node> nodes,List<Edge> edges) {
    return new Order(0L,Instant.now(),"2.0.0","SortingDemo","AGV01","o1",update,nodes,edges);
  }
  static Edge edge(String id,long seq,String start,String end,boolean released) {
    return new Edge(id,seq,released,start,end,List.of()).setLength(1.0).setMaxSpeed(1.8);
  }
  static Action action(String id,String type) {return new Action(type,id,BlockingType.HARD);}
  static SortingMqttVehicle vehicle() {
    var settings=new Properties();settings.setProperty("battery.chargePercentPerSecond","100");
    return new SortingMqttVehicle("AGV01","SortingDemo","AGV01","uagv/v2/SortingDemo/AGV01","A",0,0,settings,(t,p)->{});
  }
  public static void main(String[] args) throws Exception {
    var v=vehicle();
    var first=order(0,List.of(node("A",0,true),node("B",2,true,action("load","Load cargo")),node("C",4,false)),
        List.of(edge("AB",1,"A","B",true),edge("BC",3,"B","C",false)));
    v.order(first);v.order(first);
    for(int i=0;i<60;i++) v.tick(.1);
    require(v.state.getLastNodeId().equals("B"),"Unreleased horizon must not execute");
    require(v.loaded && v.actions.get("load").getActionStatus()==ActionStatus.FINISHED,"Load acknowledged");
    require(Math.abs(v.state.getBatteryState().getBatteryCharge()-99.99)<1e-5,"Movement consumes battery exactly once");
    v.order(first);require(!v.busy(),"Duplicate order must not execute twice");
    var update=order(1,List.of(node("B",2,true,action("load","Load cargo")),node("C",4,true,action("unload","Unload cargo"))),List.of(edge("BC",3,"B","C",true)));
    v.order(update);v.tick(.1);
    v.instant(action("pause","startPause"));double elapsed=v.motionElapsed;
    for(int i=0;i<30;i++)v.tick(.1);
    require(v.motionElapsed==elapsed,"Pause freezes movement");
    v.instant(action("resume","stopPause"));
    Path dir=Files.createTempDirectory("sorting-mqtt-test"),file=dir.resolve("checkpoint");
    v.publish();v.save(file,"test");var restored=vehicle();restored.restore(file,"test");
    for(int i=0;i<70;i++)restored.tick(.1);
    require(restored.state.getLastNodeId().equals("C")&&!restored.loaded,"Checkpoint resumes movement and unload");
    require(restored.actions.get("load").getActionStatus()==ActionStatus.FINISHED,"Stitching action is not replayed");
    // Appending a new released base while the old base is still in motion must retain old work.
    var moving=vehicle();moving.order(first);moving.tick(.1);moving.order(update);
    for(int i=0;i<100;i++)moving.tick(.1);
    require(moving.state.getLastNodeId().equals("C")&&!moving.loaded,"Live base extension executes both node actions");
    var charge=new Order(0L,Instant.now(),"2.0.0","SortingDemo","AGV01","charge",0L,
        List.of(node("C",0,true,action("charge","CHARGE"))),List.of());
    restored.state.getBatteryState().setBatteryCharge(19.0);
    restored.order(charge);for(int i=0;i<35;i++)restored.tick(.1);
    require(restored.state.getBatteryState().getBatteryCharge()==100&&!restored.state.getBatteryState().isCharging(),"Recharge reaches full then idle");
    v.instant(action("cancel","cancelOrder"));require(!v.busy()&&v.edges.isEmpty(),"Cancel releases simulated pending work");
    boolean rejected=false;try {vehicle().order(update);}catch(IllegalArgumentException expected){rejected=true;}
    require(rejected,"Wrong starting position rejected");
    Files.delete(file);Files.delete(dir);
    System.out.println("PASS: released base, duplicates, base extension, load/unload, pause, checkpoint, charging, cancellation");
  }
}
