package tid.pce.computingEngine.algorithms.sson;

import java.net.Inet4Address;
import java.util.List;
import java.util.logging.Logger;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import tid.pce.computingEngine.ComputingRequest;
import tid.pce.computingEngine.algorithms.AlgorithmReservation;
import tid.pce.computingEngine.algorithms.ComputingAlgorithm;
import tid.pce.computingEngine.algorithms.PCEPUtils;
import tid.pce.computingEngine.algorithms.utilities.bandwidthToSlotConversion;
import tid.pce.computingEngine.algorithms.utilities.graphs_comparator;
import tid.pce.pcep.constructs.EndPoint;
import tid.pce.pcep.constructs.EndPointAndRestrictions;
import tid.pce.pcep.constructs.P2MPEndpoints;
import tid.pce.pcep.constructs.P2PEndpoints;
import tid.pce.pcep.constructs.Path;
import tid.pce.pcep.constructs.Request;
import tid.pce.pcep.constructs.Response;
import tid.pce.pcep.messages.PCEPResponse;
import tid.pce.pcep.objects.Bandwidth;
import tid.pce.pcep.objects.EndPoints;
import tid.pce.pcep.objects.EndPointsIPv4;
import tid.pce.pcep.objects.ExplicitRouteObject;
import tid.pce.pcep.objects.GeneralizedEndPoints;
import tid.pce.pcep.objects.Metric;
import tid.pce.pcep.objects.Monitoring;
import tid.pce.pcep.objects.NoPath;
import tid.pce.pcep.objects.ObjectParameters;
import tid.pce.pcep.objects.RequestParameters;
import tid.pce.pcep.objects.SRERO;
import tid.pce.pcep.objects.subobjects.SREROSubobject;
import tid.pce.pcep.objects.tlvs.NoPathTLV;
import tid.pce.server.wson.ReservationManager;
import tid.pce.tedb.DomainTEDB;
import tid.pce.tedb.IntraDomainEdge;
import tid.pce.tedb.SSONInformation;
import tid.pce.tedb.TEDB;
import tid.rsvp.objects.subobjects.IPv4prefixEROSubobject;

/**
 * Implementation of the algorithm "Adaptive Unconstrained Routing Exhaustive".
 * 
 * <p>Reference: A. Mokhtar y M. Azizoglu, "Adaptive wavelength routing in all-optical networks",
 * IEEE/ACM Transactions on Networking, vol. 6, no.2 pp. 197 - 201, abril 1998</p>
 * @author arturo mayoral
 *
 */
public class SR_path_algorithm implements ComputingAlgorithm {

	/**
	 * The Logger.
	 */
	private Logger log=Logger.getLogger("PCEServer");

	/**
	 * The Path Computing Request to calculate.
	 */
	private ComputingRequest pathReq;

	/**
	 * Access to the Precomputation part of the algorithm.
	 */
	private SR_path_algorithmPreComputation preComp;

	/**
	 * Access to the Reservation Manager to make reservations of Wavalengths/labels.
	 */
	private ReservationManager reservationManager;


	private SSONInformation SSONInfo;

	//	/**
	//	 * Number of wavelenghts (labels).
	//	 */
	//private int num_lambdas;

	/**
	 * The traffic engineering database
	 */
	private DomainTEDB ted;


	private GenericLambdaReservation  reserv;
	/**
	 * Constructor
	 * @param pathReq
	 * @param ted
	 * @param reservationManager
	 */
	public SR_path_algorithm(ComputingRequest pathReq,TEDB ted, ReservationManager reservationManager, int mf){
		//this.num_lambdas=((DomainTEDB)ted).getWSONinfo().getNumLambdas();
		this.pathReq=pathReq;
		this.reservationManager=reservationManager;
		this.ted=(DomainTEDB)ted;
	}

	/**
	 * Exectutes the path computation and returns the PCEP Response
	 */
	public PCEPResponse call(){
		//Timestamp of the start of the algorithm;
		long tiempoini =System.nanoTime();
		log.finest("Starting AURE Algorithm");
		//Create the response message
		//It will contain either the path or noPath
		PCEPResponse m_resp=new PCEPResponse();
		//The request that needs to be solved
		Request req=pathReq.getRequestList().get(0);
		//Request Id, needed for the response
		long reqId=req.getRequestParameters().getRequestID();
		log.info("Request id: "+reqId+", getting endpoints");
		//Start creating the response
		Response response=new Response();
		RequestParameters rp = new RequestParameters();
		rp.setRequestID(reqId);
		response.setRequestParameters(rp);
		m_resp.addResponse(response);

		//esto hay que cambiarlo para poder leer del GENERALIZED END POINTS
		//if (getObjectType(req.getEndPoints()))
		EndPoints  EP= req.getEndPoints();
		Bandwidth  Bw= req.getBandwidth(); // Objeto bandwidth para saber la demanda de la peticion.
		Object source_router_id_addr = null;
		Object dest_router_id_addr = null;
		graphs_comparator grc = new graphs_comparator ();

		log.info("BW: "+Bw.getBw());

		int num_slots = 0;
		int cs;
		int m=0;
		// Conversión Bw a numero de slots en función de la grid.

		bandwidthToSlotConversion conversion= new bandwidthToSlotConversion();



		if (EP.getOT()==ObjectParameters.PCEP_OBJECT_TYPE_ENDPOINTS_IPV4){
			EndPointsIPv4  ep=(EndPointsIPv4) req.getEndPoints();
			source_router_id_addr=ep.getSourceIP();
			dest_router_id_addr=ep.getDestIP();
		}
		//aqu� acaba lo que he a�adido

		//Now, check if the source and destination are in the TED.
		log.info("Source: "+source_router_id_addr+"; Destination:"+dest_router_id_addr);
		if (!(((ted.containsVertex(source_router_id_addr))&&(ted.containsVertex(dest_router_id_addr))))){
			log.info("Source or destination are NOT in the TED");	
			NoPath noPath= new NoPath();
			noPath.setNatureOfIssue(ObjectParameters.NOPATH_NOPATH_SAT_CONSTRAINTS);
			NoPathTLV noPathTLV=new NoPathTLV();
			if (!((ted.containsVertex(source_router_id_addr)))){
				log.finest("Unknown source");	
				noPathTLV.setUnknownSource(true);	
			}
			if (!((ted.containsVertex(dest_router_id_addr)))){
				log.finest("Unknown destination");
				noPathTLV.setUnknownDestination(true);	
			}

			noPath.setNoPathTLV(noPathTLV);				
			response.setNoPath(noPath);
			return m_resp;
		}
		// check if src and dst are the same 
		if (source_router_id_addr.equals(dest_router_id_addr)){
			log.info("Source and destination are the same!");
			Path path=new Path();
			ExplicitRouteObject ero= new ExplicitRouteObject();
			IPv4prefixEROSubobject eroso= new IPv4prefixEROSubobject();
			eroso.setIpv4address((Inet4Address)source_router_id_addr);
			eroso.setPrefix(32);
			ero.addEROSubobject(eroso);
			path.seteRO(ero);

			if (req.getMetricList().size()!=0){
				Metric metric=new Metric();
				metric.setMetricType(req.getMetricList().get(0).getMetricType() );
				log.fine("Number of hops "+0);
				float metricValue=0;
				metric.setMetricValue(metricValue);
				path.getMetricList().add(metric);
			}
			response.addPath(path);
			long tiempofin =System.nanoTime();
			long tiempotot=tiempofin-tiempoini;
			log.info("Ha tardado "+tiempotot+" nanosegundos");
			Monitoring monitoring=pathReq.getMonitoring();
			if (monitoring!=null){
				if (monitoring.isProcessingTimeBit()){

				}
			}
			m_resp.addResponse(response);
			return m_resp;

		}


		boolean nopath=true;//Initially, we still have no path
		boolean end=false;//The search has not ended yet

		log.info("Starting the computation");



		SimpleDirectedWeightedGraph<Object,IntraDomainEdge> graphLambda=preComp.getNetworkGraphs().get(0);
		//log.info("Grafo ok "+lambda);
		//log.info("Grafo : "+preComp.printBaseTopology());
		DijkstraShortestPath<Object,IntraDomainEdge>  dsp=new DijkstraShortestPath<Object,IntraDomainEdge> (graphLambda, source_router_id_addr, dest_router_id_addr);
		GraphPath<Object,IntraDomainEdge> gp_trully_chosen=dsp.getPath();



		if (gp_trully_chosen!=null){

			Path path=new Path();
			SRERO srero = new SRERO();
			log.info("setting SRERO");
			int i;			
			List<IntraDomainEdge> edge_list=gp_trully_chosen.getEdgeList();
			for (i=0;i<edge_list.size();i++){
				SREROSubobject sreroso = new SREROSubobject();
				log.info("SRERO edge: "+edge_list.get(i));
				sreroso.setSID(edge_list.get(i).getDst_SID());
				sreroso.setLoosehop(false);
				//TODO: anyadir muchas mas variables
				srero.addSREROSubobject(sreroso);
				log.info("SRERO subobject added: "+sreroso.toString());
			}
			path.setSRERO(srero);
			PCEPUtils.completeMetric(path, req, edge_list);
			response.setBandwidth(Bw);
			response.addPath(path);
		}
		else // NO PATH FOUND
		{
			log.info("No path found"); // NO PATH FOUND
			NoPath noPath= new NoPath();
			noPath.setNatureOfIssue(ObjectParameters.NOPATH_NOPATH_SAT_CONSTRAINTS);
			NoPathTLV noPathTLV=new NoPathTLV();
			noPath.setNoPathTLV(noPathTLV);				
			response.setNoPath(noPath);				
		}	
		long tiempofin =System.nanoTime();
		long tiempotot=tiempofin-tiempoini;
		log.info("Ha tardado "+tiempotot+" nanosegundos");
		return m_resp;
	}

	public void setPreComp(SR_path_algorithmPreComputation preComp) {
		this.preComp = preComp;
	}

	public AlgorithmReservation getReserv() {
		return reserv;
	}	


}
