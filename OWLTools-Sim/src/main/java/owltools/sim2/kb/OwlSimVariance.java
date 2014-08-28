package owltools.sim2.kb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.log4j.Logger;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.graph.OWLGraphWrapper;
import owltools.sim2.OwlSim;
import owltools.sim2.UnknownOWLClassException;

/**
 * @author Tudor Groza
 *
 * Computes the variance of a given phenotypic profile against a reference model.
 * Intuitively, the variance should be lower when computed against a close matching
 * reference model than when computed against a random model. This intuition may 
 * not hold because of the possibly fairly uniform IC means of the reference models.
 * It may, however, be useful when computed per abnormality branch.
 * 
 * Idea: Perhaps a p-value computed using a single-sided or paired t-test might 
 * make more sense.
 */
public class OwlSimVariance {

	private Logger LOG = Logger.getLogger(OwlSimVariance.class);

	private OwlSim owlsim;
	private OWLOntology sourceOntology;
	private OWLGraphWrapper ontologyGraph;
	
	private Map<IRI, Double> referenceMeans;

	/**
	 * Assuming owlsim, ontologyGraph and sourceOntology objects already initialized
	 * @throws UnknownOWLClassException 
	 */
	public OwlSimVariance(OwlSim owlsim, OWLOntology sourceOntology, OWLGraphWrapper ontologyGraph) throws UnknownOWLClassException {
		this.owlsim = owlsim;
		this.sourceOntology = sourceOntology;
		this.ontologyGraph = ontologyGraph;
		
		referenceMeans = new HashMap<IRI, Double>();
		computeReferenceMeans();
	}
	
	private void computeReferenceMeans() throws UnknownOWLClassException {
		LOG.info("Computing variance for reference entities ...");

		// Compute and cache variance for all possible reference entities / disorders
		for (OWLNamedIndividual reference : sourceOntology.getIndividualsInSignature()) {
			SummaryStatistics stats = owlsim.computeIndividualStats(reference);
			referenceMeans.put(reference.getIRI(), stats.getMean());
		}
	}

	public double getVariance(Set<OWLClass> candidates, IRI referenceEntity) throws OwlSimVarianceEntityReferenceNotFoundException {
		// Reference entity does not exist - this should probably not happen.
		if (!referenceMeans.containsKey(referenceEntity)) {
			throw new OwlSimVarianceEntityReferenceNotFoundException(referenceEntity);
		}
		
		// Create IC list for candidates provided
		List<Double> icList = retrieveCandidatesIC(candidates);
		double[] icData = new double[icList.size()];
		for (int i = 0 ;i < icList.size(); i++) {
			icData[i] = icList.get(i).doubleValue();
		}
		// Return variance against the given reference concept
		return new Variance().evaluate(icData, referenceMeans.get(referenceEntity).doubleValue());
	}

	/**
	 * This implementation can be heavily optimized
	 */
	
	public Map<String, Double> getTopNVariances(Set<OWLClass> candidates, int topn) {
		List<Double> icList = retrieveCandidatesIC(candidates);
	
		double[] icData = new double[icList.size()];
		for (int i = 0 ;i < icList.size(); i++) {
			icData[i] = icList.get(i).doubleValue();
		}

		// Compute variance against all cached reference entities
		Map<IRI, Double> iResult = new LinkedHashMap<IRI, Double>();
		List<Double> list = new ArrayList<Double>();
		for (IRI reference : referenceMeans.keySet()) {
			double variance = new Variance().evaluate(icData, referenceMeans.get(reference).doubleValue());
			list.add(variance);
			iResult.put(reference, variance);
		}
		// Sort variances ascendantly
		Collections.sort(list);
		
		// Return top-N variances
		
		int iterSize = topn < list.size() ? topn : list.size();
		Map<String, Double> result = new LinkedHashMap<String, Double>();
		for (int i = 0 ;i < iterSize ; i++) {
			double varValue = list.get(i);
			List<String> refURIs = findRefURIs(varValue, iResult);
			for (String uri : refURIs) {
				result.put(uri, varValue);
			}
		}
		
		return result;
	}

	private List<Double> retrieveCandidatesIC(Set<OWLClass> candidates) {
		List<Double> icList = new ArrayList<Double>();

		// Create IC list for candidates provided
		for (OWLClass c : candidates) {
			Double ic;
			try {
				ic = owlsim.getInformationContentForAttribute(c);
				if (ic == null) { 
					if (ontologyGraph.getOWLClass(c.getIRI()) != null) {
						ic = owlsim.getSummaryStatistics().max.getMax();
					} else {
						throw new UnknownOWLClassException(c); }
				}
				if (ic.isInfinite() || ic.isNaN()) {
					ic = owlsim.getSummaryStatistics().max.getMax();
				}
				
				icList.add(ic);
			} catch (UnknownOWLClassException e) {
				LOG.info("Unknown class "+c.toStringID()+" submitted for summary stats. Removed from calculation.");
				continue;
			}
		}
		
		return icList;
	}
	
	private List<String> findRefURIs(double varValue, Map<IRI, Double> iResult) {
		List<String> list = new ArrayList<String>();

		for (IRI iri : iResult.keySet()) {
			if (iResult.get(iri).doubleValue() == varValue) {
				list.add(iri.toString());
			}
		}
		
		return list;
	}	
}