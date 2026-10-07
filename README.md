SPN_Model.xml
Complete three-layer SPN model used in the experimental scenarios.
Requires Mercury 5.0.1.

Modeling_Validation.xml
FrontEnd SPN submodel used in the independent validation experiment.
Requires Mercury 5.0.1.

SimuladorDDOSValidacao.java
Independent Java discrete-event simulator used to validate the FrontEnd
autoscaling submodel.
Requires Java 11 and XChart.
Uses 200 Monte Carlo replications and 201 sampling points from 0 to 1000 ms.


| Parameter | Main SPN | Validation |
|---|---:|---:|
| Queue capacity | 100 | 100 |
| Initial capacity | 2 | 2 |
| Scale-out replicas | 10 | 30 |
| Trigger threshold | 50% | 50% |
| Attack start | 100 ms | 200 ms |
| Provisioning delay | 1 ms | 5 ms |
| Horizon | 250 ms | 1000 ms |