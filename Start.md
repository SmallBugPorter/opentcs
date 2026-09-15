编译
bash ./gradlew installDist


cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-kernel
sh startKernel.sh


cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-modeleditor
sh startModelEditor.sh


cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-kernelcontrolcenter
sh startKernelControlCenter.sh


cd /home/lebi/opentcs_ws/opentcs/build/install/opentcs/opentcs-operationsdesk
sh startOperationsDesk.sh


cd /home/lebi/opentcs_ws/opentcs
SortingDemo/mqtt-vehicles.sh start
SortingDemo/simulation.sh start