package de.hpi.epc;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import de.hpi.bpt.hypergraph.abs.IGObject;
import de.hpi.bpt.process.epc.Connector;
import de.hpi.bpt.process.epc.Event;
import de.hpi.bpt.process.epc.Function;
import de.hpi.bpt.process.epc.IControlFlow;
import de.hpi.bpt.process.epc.IEPC;
import de.hpi.bpt.process.epc.IFlowObject;
import de.hpi.bpt.process.epc.ProcessInterface;
/*
 * Due to the use off unmodifiable jar library, cast cannot be fully fixed
 */
@SuppressWarnings("unchecked")
public class Marking implements Cloneable {
	public enum State {
		POS_TOKEN, NEG_TOKEN, NO_TOKEN
	}

	public enum Context {
		WAIT, DEAD
	}

	public class NodeNewMarkingPair {
		public IFlowObject node;
		public Marking newMarking;

		public NodeNewMarkingPair(IFlowObject node, Marking newMarking) {
			this.node = node;
			this.newMarking = newMarking;
		}
				
		public String toString(){
			return node.getId() + newMarking.toString();
		}
	}

	public HashMap<IControlFlow, State> state;
	public HashMap<IControlFlow, Context> context;

	public Marking() {
		this(new HashMap<IControlFlow, State>(),
				new HashMap<IControlFlow, Context>());
	}

	public Marking(HashMap<IControlFlow, State> state,
			HashMap<IControlFlow, Context> context) {
		this.state = state;
		this.context = context;
	}

	public Marking clone() {
		return new Marking((HashMap<IControlFlow, State>) state.clone(),
				(HashMap<IControlFlow, Context>) context.clone());
	}
	
	public boolean equals(Object o){
		//System.err.println("Remark: Marking#equals seems not to work properly!!");
		Marking m = (Marking)o;
		if(state.size() != m.state.size() || context.size() != m.context.size()){
			return false;
		}
		return toString().equals(m.toString());
	}

	public String toString(){
		String s = "";
		
		List<IControlFlow> cfs = new LinkedList<IControlFlow>((Set<IControlFlow>)state.keySet());
		Collections.sort(cfs);
		
		for(IControlFlow cf : cfs){
			s+= "|";
			s+=cf.getId();
			s+=" ";
			if(state.get(cf) == State.NEG_TOKEN){
				s += "-";
			} else if (state.get(cf) == State.POS_TOKEN){
				s += "+";
			} else {
				s += "0";
			}
			if(context.get(cf) == Context.WAIT){
				s += "W";
			} else {
				s += "D";
			}
		}
		
		return s;
	}
	
	public LinkedList<NodeNewMarkingPair> propagate(IEPC diag) {
		propagateDeadContext(diag);
		propagateWaitContext(diag);
		propagateNegativeTokens(diag);
		return propagatePositiveTokens(diag);
	}

	private void propagateDeadContext(IEPC diag) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (IFlowObject node : (Collection<IFlowObject>)diag.getFlowObjects()) {
				if ( // if one incoming edge have dead context ...
				filterByContext(diag.getIncomingControlFlow(node), Context.DEAD).size() > 0
						&&
						// ... and if one of outgoing edges without token have
						// wait context
						filterByContext(
								filterByState(diag.getOutgoingControlFlow(node),
										State.NO_TOKEN), Context.WAIT).size() > 0) {
					applyContext(filterByState(diag.getOutgoingControlFlow(node),
							State.NO_TOKEN), Context.DEAD);
					changed = true;
				}
			}
		}
	}

	private void propagateWaitContext(IEPC diag) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (IFlowObject node : (Collection<IFlowObject>)diag.getFlowObjects()) {
				// If event, function or split-connector
				if (diag.getIncomingControlFlow(node).size() == 1) {
					if (context.get(diag.getIncomingControlFlow(node).iterator().next()) == Context.WAIT
							&& filterByContext(diag.getOutgoingControlFlow(node),
									Context.DEAD).size() > 0) {
						for (IControlFlow outEdge : (Collection<IControlFlow>)diag.getOutgoingEdges(node)) {
							// Only put new dead context if there is no token
							if (state.get(outEdge) == State.NO_TOKEN) {
								applyContext(outEdge, Context.WAIT);
								changed = true;
							}
						}
					}
					// AND Join
				} else if ( isAndConnector(node) ) {
					if (filterByContext(diag.getIncomingControlFlow(node), Context.WAIT)
							.size() == diag.getIncomingControlFlow(node).size()
							&& context.get(diag.getOutgoingControlFlow(node).iterator().next()) == Context.DEAD
							&& state.get(diag.getOutgoingControlFlow(node).iterator().next()) == State.NO_TOKEN) {
						applyContext(diag.getOutgoingControlFlow(node), Context.WAIT);
						changed = true;
					}
					// Xor/ Or Join
				} else if ( isXorConnector(node) || isOrConnector(node)) {
					if (filterByContext(diag.getIncomingControlFlow(node), Context.WAIT).size() > 0 &&
							state.get(diag.getOutgoingControlFlow(node).iterator().next()) == State.NO_TOKEN &&
							context.get(diag.getOutgoingControlFlow(node).iterator().next()) != Context.WAIT) {
						applyContext(diag.getOutgoingControlFlow(node), Context.WAIT);
						changed = true;
					}
				}
			}
		}
	}

	private void propagateNegativeTokens(IEPC diag) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (IFlowObject node : (Collection<IFlowObject>) diag.getFlowObjects()) {
				// if all input arcs hold negative tokens and if there is no
				// positive token on the output arc
				if (diag.getIncomingControlFlow(node).size() > 0
						&& filterByState(diag.getIncomingControlFlow(node),
								State.NEG_TOKEN).size() == diag.getIncomingControlFlow(node).size()
						&& filterByState(diag.getOutgoingControlFlow(node),
								State.POS_TOKEN).size() == 0) {
					applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);
					applyState(diag.getOutgoingControlFlow(node), State.NEG_TOKEN);
					applyContext(diag.getOutgoingControlFlow(node), Context.DEAD);
					changed = true;
				}
			}
		}
	}

	/*
	 * This method applies firing rules without changing the marking: A list of
	 * possible nodes with new marking is returned.
	 */
	private LinkedList<NodeNewMarkingPair> propagatePositiveTokens(IEPC diag) {
		LinkedList<NodeNewMarkingPair> nodeNewMarkings = new LinkedList<NodeNewMarkingPair>();

		// collect nodes which can fire
		for (IFlowObject node : (Collection<IFlowObject>)diag.getFlowObjects()) {
			// Event, functions and split connectors
			if (diag.getIncomingControlFlow(node).size() == 1 && state.get(diag.getIncomingControlFlow(node).iterator().next()) == State.POS_TOKEN) {
				// (a), (b), (c)
				if (node instanceof ProcessInterface || node instanceof Function || node instanceof Event || isAndConnector(node) ) {
					NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(node, this.clone());

					nodeNewMarking.newMarking.applyContext(diag.getIncomingControlFlow(node), Context.DEAD);
					nodeNewMarking.newMarking.applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);

					nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow(node), Context.WAIT);
					nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow(node), State.POS_TOKEN);

					nodeNewMarkings.add(nodeNewMarking);
					// (e)
				} else if ( Marking.isXorConnector(node)) {
					// Each of the outgoing edges can receive a token
					for (IControlFlow edge : (Collection<IControlFlow>)diag.getOutgoingControlFlow(node)) {
						NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(
								node, this.clone());

						nodeNewMarking.newMarking.applyContext(diag.getIncomingControlFlow(node), Context.DEAD);
						nodeNewMarking.newMarking.applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);

						nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow(node), Context.DEAD);
						nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow(node), State.NO_TOKEN);

						nodeNewMarking.newMarking.applyContext(edge,
								Context.WAIT);
						nodeNewMarking.newMarking.applyState(edge,
								State.POS_TOKEN);

						nodeNewMarkings.add(nodeNewMarking);
					}
					// (g)
				} else if (Marking.isOrConnector(node)) {
					List<IControlFlow> controlFlowList = new LinkedList<IControlFlow>((Collection<IControlFlow>)diag.getOutgoingControlFlow(node));
					for (List<IControlFlow> edges : (List<List<IControlFlow>>) de.hpi.bpmn.analysis.Combination.findCombinations(controlFlowList)) {
						if (edges.size() == 0)
							continue;

						NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(
								node, this.clone());

						nodeNewMarking.newMarking.applyContext(diag.getIncomingControlFlow(node), Context.DEAD);
						nodeNewMarking.newMarking.applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);

						nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow(node), Context.DEAD);
						nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow(node), State.NEG_TOKEN);

						nodeNewMarking.newMarking.applyContext(edges,
								Context.WAIT);
						nodeNewMarking.newMarking.applyState(edges,
								State.POS_TOKEN);

						nodeNewMarkings.add(nodeNewMarking);
					}
				}
				// join connectors
			} else if (diag.getOutgoingControlFlow(node).size() == 1) {
				// (d)
				if (Marking.isAndConnector(node)
						&& filterByState(diag.getIncomingControlFlow(node),
								State.POS_TOKEN).size() == diag.getIncomingControlFlow(node).size()) {
					NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(
							node, this.clone());

					nodeNewMarking.newMarking.applyContext(diag.getIncomingControlFlow(node), Context.DEAD);
					nodeNewMarking.newMarking.applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);

					nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow(node), Context.WAIT);
					nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow(node), State.POS_TOKEN);

					nodeNewMarkings.add(nodeNewMarking);
				} else if (Marking.isXorConnector(node)
						&& filterByState(diag.getIncomingControlFlow(node),
								State.POS_TOKEN).size() >= 1) {
					NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(
							node, this.clone());

					nodeNewMarking.newMarking
							.applyContext(filterByState(
									diag.getIncomingControlFlow(node), State.POS_TOKEN),
									Context.DEAD);
					nodeNewMarking.newMarking.applyState(diag.getIncomingControlFlow(node), State.NO_TOKEN);

					nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow(node), Context.WAIT);
					nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow(node), State.POS_TOKEN);

					nodeNewMarkings.add(nodeNewMarking);
					// (h)
				} else if (Marking.isOrConnector(node)){
					Collection<IControlFlow> incomingControlFlow = diag.getIncomingControlFlow((Connector)node);
					// Collect the control flows which would enable the or join to fire
					// (pos + neg tokens, dead context)
					Collection<IControlFlow> cfReadyForFiring = filterByState(incomingControlFlow, State.POS_TOKEN);
					// There should be at least one positive token
					if(cfReadyForFiring.size() > 0){
						cfReadyForFiring.addAll(filterByState(incomingControlFlow, State.NEG_TOKEN));
						cfReadyForFiring.addAll(filterByContext(incomingControlFlow, Context.DEAD));
						// Are all incomingControlFlow ready for firing?
						//TODO perhaps just ask for cf with wait context?
						if(cfReadyForFiring.containsAll(incomingControlFlow)){
							NodeNewMarkingPair nodeNewMarking = new NodeNewMarkingPair(
									node, this.clone());
							
							nodeNewMarking.newMarking.applyContext(incomingControlFlow, Context.DEAD);
							nodeNewMarking.newMarking.applyState(incomingControlFlow, State.NO_TOKEN);
							
							nodeNewMarking.newMarking.applyState(diag.getOutgoingControlFlow((Connector)node), State.POS_TOKEN);
							nodeNewMarking.newMarking.applyContext(diag.getOutgoingControlFlow((Connector)node), Context.WAIT);
							
							//Remove all negative token from negative upper corona, see p. 76
							nodeNewMarking.newMarking.cleanUpperNegativeCorona(diag, nodeNewMarking.node);
							
							nodeNewMarkings.add(nodeNewMarking);
						}
					}
				}
			}
		}

		return nodeNewMarkings;
	}

	public Collection<IControlFlow> filterByContext(Collection<IControlFlow> edges,
			Context type) {
		Collection<IControlFlow> filtered = new LinkedList<IControlFlow>();
		for (IControlFlow edge : edges) {
			if (context.get(edge) == type) {
				filtered.add(edge);
			}
		}
		return filtered;
	}

	public Collection<IControlFlow> filterByState(Collection<IControlFlow> edges, State type) {
		Collection<IControlFlow> filtered = new LinkedList<IControlFlow>();
		for (IControlFlow edge : edges) {
			if (state.get(edge) == type) {
				filtered.add(edge);
			}
		}
		return filtered;
	}
	
	public static Marking getInitialMarking(IEPC diag, List<IFlowObject> startNodes){
		Marking marking = new Marking();
		for (IControlFlow edge : (Collection<IControlFlow>)diag.getControlFlow()) {
			if(Marking.isStartArc(edge, diag)){
				if(startNodes.contains(edge.getSource())){
					// If enabled start arc
					marking.applyContext(edge, Marking.Context.WAIT);
					marking.applyState(edge, Marking.State.POS_TOKEN);
				} else {
					// If not enabled start arc
					marking.applyContext(edge, Marking.Context.DEAD);
					marking.applyState(edge, Marking.State.NEG_TOKEN);
				}
			} else {
				// If normal arc
				marking.applyContext(edge, Marking.Context.WAIT);
				marking.applyState(edge, Marking.State.NO_TOKEN);
			}
		}
		
		return marking;
	}
	
	// Adapted from Mendling, p. 80
	public boolean isFinalMarking(IEPC diag){
		// at least one end arc should have pos token, 
		// and no intermediate arc should have a pos token 
		boolean tokenForEndArcFound = false;
		for(IControlFlow cf : state.keySet()){
			if(diag.getOutgoingControlFlow((IFlowObject)cf.getTarget()).size() == 0){
				if(state.get(cf) == State.POS_TOKEN){
					tokenForEndArcFound = true;
				}
			} else {
				if(state.get(cf) == State.POS_TOKEN){
					return false;
				}
			}
		}
		return tokenForEndArcFound; 
	}
	
	/* Cleans the negative upper corona: "remove all negative tokens on its
	 * so-called negative upper corona, i.e. the arcs carrying a negative token that have a path to
	 * the OR-join on which each arc has a dead context and no token on it." (p. 76)
	 */
	public void cleanUpperNegativeCorona(IEPC epc, IFlowObject node){
		cleanUpperNegativeCorona(epc, node, new LinkedList<IControlFlow>());
	}
	public void cleanUpperNegativeCorona(IEPC epc, IFlowObject node, LinkedList<IControlFlow> visited){
		for(IControlFlow cf : (Collection<IControlFlow>)epc.getIncomingControlFlow(node)){
			if(context.get(cf).equals(Context.DEAD)){
				if(state.get(cf).equals(State.NO_TOKEN) && !visited.contains(cf)){
					visited.add(cf);
					cleanUpperNegativeCorona(epc, (IFlowObject)cf.getSource(), visited);
				} else if (state.get(cf).equals(State.NEG_TOKEN)){
					applyState(cf, State.NO_TOKEN);
				} else {
					// do nothing
				}
			}
		}
	}

	public void applyState(Collection<IControlFlow> edges, State type) {
		for (IControlFlow edge : edges) {
			applyState(edge, type);
		}
	}

	public void applyState(IControlFlow edge, State type) {
		state.put(edge, type);
	}

	public void applyContext(Collection<IControlFlow> edges, Context type) {
		for (IControlFlow edge : edges) {
			applyContext(edge, type);
		}
	}

	public void applyContext(IControlFlow edge, Context type) {
		context.put(edge, type);
	}
	
	public boolean hasToken(IControlFlow edge){
		return state.get(edge).equals(State.POS_TOKEN);
	}
	
	/* Calculates if at least one positive token is in the graph
	 */
	public boolean hasToken(IEPC epc){
		for(IControlFlow cf : (Collection<IControlFlow>)epc.getControlFlow()){
			if(hasToken(cf))
				return true;
		}
		return false;
	}
	
	//TODO Move to another class!
	static public List<IControlFlow> getStartArcs(IEPC epc){
		List<IControlFlow> cfs = new LinkedList<IControlFlow>();
		for(IControlFlow cf : (Collection<IControlFlow>)epc.getControlFlow()){
			if(epc.getIncomingControlFlow((IFlowObject)cf.getSource()).size() == 0){
				cfs.add(cf);
			}
		}
		return cfs;
	}
	
	//TODO Move to another class!
	static public List<IControlFlow> getEndArcs(IEPC epc){
		List<IControlFlow> cfs = new LinkedList<IControlFlow>();
		for(IControlFlow cf : (Collection<IControlFlow>)epc.getControlFlow()){
			if(epc.getOutgoingControlFlow((IFlowObject)cf.getTarget()).size() == 0){
				cfs.add(cf);
			}
		}
		return cfs;
	}
	
	static public boolean isAndConnector(IGObject flowObject){
		return (flowObject instanceof Connector) && ((Connector)flowObject).getConnectorType().equals(de.hpi.bpt.process.epc.ConnectorType.AND);
	}
	static public boolean isOrConnector(IGObject flowObject){
		return (flowObject instanceof Connector) && ((Connector)flowObject).getConnectorType().equals(de.hpi.bpt.process.epc.ConnectorType.OR);
	}
	static public boolean isXorConnector(IGObject flowObject){
		return (flowObject instanceof Connector) && ((Connector)flowObject).getConnectorType().equals(de.hpi.bpt.process.epc.ConnectorType.XOR);
	}
	static public boolean isSplit(IGObject flowObject, IEPC diag){
		return (flowObject instanceof Connector) &&  diag.getOutgoingControlFlow((Connector)flowObject).size() > 1;
	}
	static public boolean isJoin(IGObject flowObject, IEPC diag){
		return (flowObject instanceof Connector) &&  diag.getIncomingControlFlow((Connector)flowObject).size() > 1;
	}
	static public boolean isStartArc(IControlFlow cf, IEPC diag){
		return diag.getIncomingControlFlow((IFlowObject)cf.getSource()).size() == 0;
	}
}
