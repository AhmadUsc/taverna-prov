package org.purl.wf4ever.provtaverna.export;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.UUID;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import net.sf.taverna.raven.appconfig.ApplicationConfig;
import net.sf.taverna.t2.provenance.api.ProvenanceAccess;
import net.sf.taverna.t2.provenance.lineageservice.URIGenerator;
import net.sf.taverna.t2.provenance.lineageservice.utils.DataflowInvocation;
import net.sf.taverna.t2.provenance.lineageservice.utils.Port;
import net.sf.taverna.t2.provenance.lineageservice.utils.ProcessorEnactment;
import net.sf.taverna.t2.provenance.lineageservice.utils.ProvenanceProcessor;
import net.sf.taverna.t2.provenance.lineageservice.utils.WorkflowRun;
import net.sf.taverna.t2.reference.ErrorDocument;
import net.sf.taverna.t2.reference.IdentifiedList;
import net.sf.taverna.t2.reference.StackTraceElementBean;
import net.sf.taverna.t2.reference.T2Reference;
import net.sf.taverna.t2.reference.T2ReferenceType;
import net.sf.taverna.t2.spi.SPIRegistry;

import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.WriterGraphRIOT;
import org.apache.jena.riot.system.RiotLib;
import org.apache.log4j.Logger;
import org.purl.wf4ever.provtaverna.owl.TavernaProvModel;
import org.purl.wf4ever.robundle.Bundle;
import org.purl.wf4ever.robundle.manifest.Agent;
import org.purl.wf4ever.robundle.manifest.Manifest;
import org.purl.wf4ever.robundle.manifest.PathAnnotation;

import uk.org.taverna.databundle.DataBundles;
import uk.org.taverna.scufl2.api.common.URITools;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.io.ReaderException;
import uk.org.taverna.scufl2.api.io.WorkflowBundleIO;
import uk.org.taverna.scufl2.api.io.WorkflowBundleReader;
import uk.org.taverna.scufl2.api.io.WorkflowBundleWriter;
import uk.org.taverna.scufl2.translator.t2flow.T2FlowReader;

import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.sparql.util.Context;
import com.hp.hpl.jena.sparql.vocabulary.FOAF;

public class W3ProvenanceExport {

    private static final String WORKFLOW_BUNDLE = "application/vnd.taverna.scufl2.workflow-bundle";
    // TODO: Avoid this Taverna 2 dependency
    private static SPIRegistry<WorkflowBundleReader> readerSpi = new SPIRegistry<>(WorkflowBundleReader.class);
    private static SPIRegistry<WorkflowBundleWriter> writerSpi = new SPIRegistry<>(WorkflowBundleWriter.class);

    private static final String EN = "en";
    
    private static final String TXT = ".txt";

    private static final int EMBEDDED_STRING_MAX_FILESIZE = 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
    
	private URITools uriTools = new URITools();

	private static Logger logger = Logger.getLogger(W3ProvenanceExport.class);

	protected Map<T2Reference, Path> seenReferences = new HashMap<>();

	private static final int NANOSCALE = 9;

	private ProvenanceAccess provenanceAccess;

	private DatatypeFactory datatypeFactory;

	private ProvenanceURIGenerator uriGenerator = new ProvenanceURIGenerator();

	private String workflowRunId;

	private Map<Path, T2Reference> fileToT2Reference = Collections.emptyMap();

	private Saver saver;
	
	private URI baseURI = URI.create("app://" + UUID.randomUUID() + "/");
	
	private Map<URI, Individual> describedEntities = new HashMap<URI, Individual>();

    private TavernaProvModel provModel = new TavernaProvModel();

	public Path getBaseFolder() {
		return bundle.getRoot();
	}

	public Map<Path, T2Reference> getFileToT2Reference() {
		return fileToT2Reference;
	}

//	protected <I> void repopulateRegistry(ServiceRegistry<?, I> registry,
//			Class<I> spi) {
//		ClassLoader cl = classLoaderForServiceLoader(spi);
//		logger.info("Selected classloader " + cl + " for registry of " + spi);
//		for (I service : ServiceLoader.load(spi, cl)) {
//			registry.add(service);
//		}
//	}

//	private ClassLoader classLoaderForServiceLoader(Class<?> mustHave) {
//		List<ClassLoader> possibles = Arrays.asList(Thread.currentThread()
//				.getContextClassLoader(), getClass().getClassLoader(), mustHave
//				.getClassLoader());
//
//		for (ClassLoader cl : possibles) {
//			if (cl == null) {
//				continue;
//			}
//			try {
//				if (cl.loadClass(mustHave.getCanonicalName()) == mustHave) {
//					return cl;
//				}
//			} catch (ClassNotFoundException e) {
//			}
//		}
//		// Final fall-back, the old..
//		return ClassLoader.getSystemClassLoader();
//	}

	public W3ProvenanceExport(ProvenanceAccess provenanceAccess,
			String workflowRunId, Saver saver) {
		this.saver = saver;
		this.setWorkflowRunId(workflowRunId);
		this.setProvenanceAccess(provenanceAccess);


		try {
			datatypeFactory = DatatypeFactory.newInstance();
		} catch (DatatypeConfigurationException e) {
			throw new IllegalStateException(
					"Can't find a DatatypeFactory implementation", e);
		}
		prepareScufl2();
		
	}

	protected void prepareScufl2() {
	    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
	    wfBundleIO = new WorkflowBundleIO();
	    DataBundles.setWfBundleIO(wfBundleIO);
	    wfBundleIO.setReaders(readerSpi.getInstances());
	    wfBundleIO.setWriters(writerSpi.getInstances());
    }

    private final class ProvenanceURIGenerator extends URIGenerator {

		// Make URIs match with Scufl2
		@Override
		public String makeWorkflowURI(String workflowID) {
			return makeWorkflowBundleURI(workflowRunId) + "workflow/"
					+ provenanceAccess.getWorkflowNameByWorkflowID(workflowID)
					+ "/";
		}

		public String makeWorkflowBundleURI(String workflowRunId) {

			return "http://ns.taverna.org.uk/2010/workflowBundle/"
					+ provenanceAccess.getTopLevelWorkflowID(workflowRunId)
					+ "/";
		}

		public String makePortURI(String wfId, String pName, String vName,
				boolean inputPort) {
			String base;
			if (pName == null) {
				base = makeWorkflowURI(wfId);
			} else {
				base = makeProcessorURI(pName, wfId);
			}
			return base + (inputPort ? "in/" : "out/") + escape(vName);
		}

//		public String makeDataflowInvocationURI(String workflowRunId,
//				String dataflowInvocationId) {
//			return makeWFInstanceURI(workflowRunId) + "workflow/"
//					+ dataflowInvocationId + "/";
//		}

		public String makeProcessExecution(String workflowRunId,
				String processEnactmentId) {
			return makeWFInstanceURI(workflowRunId) + "process/"
					+ processEnactmentId + "/";
		}
	}

	enum Direction {
		INPUTS("in"), OUTPUTS("out");
		private final String path;

		Direction(String path) {
			this.path = path;

		}

		public String getPath() {
			return path;
		}
	}

	public void exportAsW3Prov()
			throws IOException {

	    Path provFile = DataBundles.getWorkflowRunProvenance(bundle);
	    
		// TODO: Make this thread safe using contexts?

		GregorianCalendar startedProvExportAt = new GregorianCalendar();

		runURI = URI.create(uriGenerator.makeWFInstanceURI(getWorkflowRunId()));

		URI provFileUri  = toURI(provFile);
        Individual bundle = provModel.createBundle(provFileUri);
        

		// Mini-provenance about this provenance trace. Unkown URI for
		// agent/activity

		
		Individual storeProvenance = provModel.createActivity(provFileUri.resolve("#taverna-prov-export"));
		storeProvenance.setLabel("taverna-prov export of workflow run provenance", EN);

		provModel.setStartedAtTime(storeProvenance, startedProvExportAt);
		
		// The agent is an execution of the Taverna software (e.g. also an
		// Activity)
		Individual tavernaAgent = provModel.createTavernaEngine(provFileUri.resolve("#taverna-engine"));
		
        String versionName = applicationConfig.getName();
		Individual plan = provModel.createPlan(URI.create("http://ns.taverna.org.uk/2011/software/"
		        + versionName));
		plan.setLabel(applicationConfig.getTitle(), EN);
		
		provModel.setWasAssociatedWith(storeProvenance, tavernaAgent, plan);
		provModel.setWasGeneratedBy(bundle, storeProvenance);

		Individual wfProcess = provModel.createWorkflowRun(runURI);
		
		bundle.setPropertyValue(FOAF.primaryTopic, wfProcess);
		
		DataflowInvocation dataflowInvocation = provenanceAccess
				.getDataflowInvocation(getWorkflowRunId());
		
		
		String workflowName = provenanceAccess
				.getWorkflowNameByWorkflowID(dataflowInvocation.getWorkflowId());
		label(wfProcess, "Workflow run of " + workflowName);

		provModel.setWasInformedBy(storeProvenance,  wfProcess);
		String wfUri = uriGenerator.makeWorkflowURI(dataflowInvocation
		        .getWorkflowId());
		Individual wfPlan = provModel.createWorkflow(URI.create(wfUri));
		provModel.setWasEnactedBy(wfProcess, tavernaAgent, wfPlan);
		provModel.setDescribedByWorkflow(wfProcess, wfPlan);
		
		provModel.setStartedAtTime(wfProcess,
				timestampToLiteral(dataflowInvocation.getInvocationStarted()));
		provModel.setStartedAtTime(wfProcess,
				timestampToLiteral(dataflowInvocation.getInvocationEnded()));

		
		
		
		
		// Workflow inputs and outputs
		storeEntitities(dataflowInvocation.getInputsDataBindingId(), wfProcess,
				Direction.INPUTS, true);
		// FIXME: These entities come out as "generated" by multiple processes
		storeEntitities(dataflowInvocation.getOutputsDataBindingId(),
				wfProcess, Direction.OUTPUTS, true);
		List<ProcessorEnactment> processorEnactments = provenanceAccess
				.getProcessorEnactments(getWorkflowRunId());
		// This will also include processor enactments in nested workflows
		for (ProcessorEnactment pe : processorEnactments) {
			String parentId = pe.getParentProcessorEnactmentId();
			URI parentURI;
			if (parentId == null) {
				// Top-level workflow
				parentURI = runURI;
			} else {
				// inside nested wf - this will be parent processenactment
				parentURI = URI.create(uriGenerator.makeProcessExecution(
						pe.getWorkflowRunId(), pe.getProcessEnactmentId()));
			}

			URI processURI = URI.create(uriGenerator.makeProcessExecution(
					pe.getWorkflowRunId(), pe.getProcessEnactmentId()));
			
			Individual process = provModel.createProcessRun(processURI);
			Individual parentProcess = provModel.createWorkflowRun(parentURI);
			provModel.setWasPartOfWorkflowRun(process, parentProcess);
			
			
			
			provModel.setStartedAtTime(process, 
					timestampToLiteral(pe.getEnactmentStarted()));
			provModel.setEndedAtTime(process, 
					timestampToLiteral(pe.getEnactmentEnded()));

			ProvenanceProcessor provenanceProcessor = provenanceAccess
					.getProvenanceProcessor(pe.getProcessorId());
			
			URI processorURI = URI.create(uriGenerator.makeProcessorURI(
					provenanceProcessor.getProcessorName(),
					provenanceProcessor.getWorkflowId()));
			
			label(process,
					"Processor execution "
							+ provenanceProcessor.getProcessorName() + " ("
							+ pe.getProcessIdentifier() + ")");
			Individual procPlan = provModel.createProcess(processorURI);
			label(procPlan, "Processor " + provenanceProcessor.getProcessorName());
	        provModel.setWasEnactedBy(process, tavernaAgent, procPlan);
	        provModel.setDescribedByProcess(process, procPlan);
	        
			URI parentWfUri = URI.create(uriGenerator.makeWorkflowURI(provenanceProcessor.getWorkflowId()));
			
	        Individual parentWf = provModel.createWorkflow(parentWfUri);
	        provModel.addSubProcess(parentWf, procPlan);
	        
			// TODO: How to link together iterations on a single processor and
			// the collections
			// they are iterating over and creating?
			// Need 'virtual' ProcessExecution for iteration?

			// TODO: Activity/service details from definition?

			// Inputs and outputs
			storeEntitities(pe.getInitialInputsDataBindingId(), process,
					Direction.INPUTS, false);
			storeEntitities(pe.getFinalOutputsDataBindingId(), process,
					Direction.OUTPUTS, false);
		}

		storeFileReferences();

		provModel.setEndedAtTime(storeProvenance, new GregorianCalendar());
		
//		provModel.model.write(outStream, "TURTLE", provFileUri.toASCIIString());
		
		OntModel model = provModel.model;
		try (OutputStream outStream = Files.newOutputStream(provFile)) {		    
    		WriterGraphRIOT writer = RDFDataMgr.createGraphWriter(RDFFormat.TURTLE_BLOCKS);
    	    writer.write(outStream, model.getBaseModel().getGraph(), RiotLib.prefixMap(model.getGraph()), provFileUri.toString(), new Context());
		} finally {
		    // Avoid registering the RIOT readers/writers from ARQ, as that won't 
		    // work within Raven or OSGi
		    provModel.resetJena();
	        logger.warn("Reset Jena readers and writers");
		}
	    
		// Evil hack - convert folder to DataBundle
		
		byte[] dataflow = getDataflow(dataflowInvocation);
        try {
            WorkflowBundle wfBundle = wfBundleIO.readBundle(new ByteArrayInputStream(dataflow), 
                    T2FlowReader.APPLICATION_VND_TAVERNA_T2FLOW_XML);
            writeBundle(wfBundle);
        } catch (ReaderException e) {
            logger.warn("Could not write bundle", e);
        }
		
	}

    private byte[] getDataflow(DataflowInvocation dataflowInvocation) {
        // you are not going to believe this...!
		for (final WorkflowRun run : 
		        provenanceAccess.listRuns(dataflowInvocation.getWorkflowId(), null)) {
		    if (getWorkflowRunId().equals(run.getWorkflowRunId())) {
                return  run.getDataflowBlob();
		    }
		}
		throw new IllegalStateException("Can't find dataflow blob for run " + getWorkflowRunId());
    }

	protected void label(Individual obj, String label)  {
	    obj.setLabel(label, EN);
	}

	protected Literal timestampToLiteral(
			Timestamp timestamp) {
	    if (timestamp == null) { 
	        return null;
	    }
		GregorianCalendar cal = new GregorianCalendar();
		cal.setTime(timestamp);
		XMLGregorianCalendar xmlCal = datatypeFactory
				.newXMLGregorianCalendar(cal);
		// Chop of the trailing 0-s of non-precission
		xmlCal.setFractionalSecond(BigDecimal.valueOf(
				timestamp.getNanos() / 1000000, NANOSCALE - 6));
        return provModel.model.createTypedLiteral(xmlCal.toXMLFormat(),
                XSDDatatype.XSDdateTime);
	}

	protected void storeFileReferences() {

		for (Entry<Path, T2Reference> entry : getFileToT2Reference().entrySet()) {
		    Path file = entry.getKey();
		  
		    try {
    			T2Reference t2Ref = entry.getValue();
    			URI dataURI = URI.create(uriGenerator.makeT2ReferenceURI(t2Ref.toUri()
    					.toASCIIString()));
    
    			Individual entity = provModel.createArtifact(dataURI);
    			if (! Files.exists(file)) {
    	            continue;
    	        }
                Individual content = provModel.setContent(entity, toURI(file));
    
                // Add checksums
    			String sha1 = saver.getSha1sums().get(file.toRealPath());
    			if (sha1 != null) {
    			    content.addLiteral(provModel.sha1, sha1);
    			}			
    			String sha512 = saver.getSha512sums().get(file.toRealPath());
    			if (sha512 != null) {
                    content.addLiteral(provModel.sha512, sha512);
    			}
    			long byteCount = Files.size(file);
                content.addLiteral(provModel.byteCount, byteCount);
    			// Add text content if it's "tiny"
    			if (file.getFileName().endsWith(TXT) && byteCount < EMBEDDED_STRING_MAX_FILESIZE) {
                    byte[] bytes = Files.readAllBytes(file);
                    String str = new String(bytes, UTF8);
                    content.addLiteral(provModel.chars, str);
                    content.addLiteral(provModel.characterEncoding, UTF8.name());
        		}
            } catch (IOException e) {
                logger.warn("Could not read " + file + " as " + UTF8, e);
            }
		}
	}

	protected URI toURI(Path file) {
	    return baseURI.resolve(getBaseFolder().toUri().relativize(file.toUri()));
    }

    protected void storeEntitities(String dataBindingId, Individual activity,
			Direction direction, boolean isTopLevel) throws IOException
			 {

		Map<Port, T2Reference> bindings = provenanceAccess
				.getDataBindings(dataBindingId);

		for (Entry<Port, T2Reference> binding : bindings.entrySet()) {
			Port port = binding.getKey();
			T2Reference t2Ref = binding.getValue();

			Individual entity = describeEntity(t2Ref);
			
			if (isTopLevel) {
			    Path ports;
                if (direction == Direction.INPUTS) {
                    ports = DataBundles.getInputs(bundle);
                } else {
                    ports = DataBundles.getOutputs(bundle); 
                }
                Path portPath = DataBundles.getPort(ports, port.getPortName());
                saveValue(t2Ref, portPath);
            } else if (!seenReference(t2Ref)) {
                saveIntermediate(t2Ref);        		
			}

//			String id = t2Ref.getLocalPart();
			//String prefix = id.substring(0, 2);
			Individual involvement;
			if (direction == Direction.INPUTS) {
			    involvement = provModel.setUsedInput(activity, entity);
			} else {
			    involvement = provModel.setWasOutputFrom(entity, activity);
			}

			String processorName = null;
			if (port.getProcessorId() != null) {
				// Not a workflow port
				ProvenanceProcessor p = provenanceAccess
						.getProvenanceProcessor(port.getProcessorId());
				processorName = p.getProcessorName();
			}
			URI portURI = URI.create(uriGenerator.makePortURI(port.getWorkflowId(),
					processorName, port.getPortName(), port.isInputPort()));
			
			Individual portRole;
			if (port.isInputPort()) {
			    portRole = provModel.createInputParameter(portURI);
			} else {
                portRole = provModel.createOutputParameter(portURI);			    
			}
			
			portRole.setLabel(port.getPortName(), "");
			if (processorName == null) {
			    portRole.setComment(
						"Workflow"
								+ (port.isInputPort() ? " input " : " output ")
								+ port.getPortName(), EN);
			} else {
                portRole.setComment(
						processorName
								+ (port.isInputPort() ? " input " : " output ")
								+ port.getPortName(), EN);
			}
			provModel.setDescribedByParameter(entity, portRole, involvement);

		}

	}

	protected Individual describeEntity(T2Reference t2Ref)
			throws IOException {
		URI dataURI = URI.create(uriGenerator.makeT2ReferenceURI(t2Ref.toUri()
				.toASCIIString()));

		Individual artifact = describedEntities.get(dataURI);
		if (artifact != null) {
			return artifact;
		}
		artifact = provModel.createArtifact(dataURI);
		describedEntities.put(dataURI, artifact);

		if (t2Ref.getReferenceType() == T2ReferenceType.ErrorDocument) {
		    Individual error = provModel.createError(dataURI);
			ErrorDocument errorDoc = saver.getReferenceService()
					.getErrorDocumentService().getError(t2Ref);
			addMessageIfNonEmpty(error, errorDoc.getMessage());
			// getExceptionMEssage added by addStackTrace
			addStackTrace(error, errorDoc);
		} else if (t2Ref.getReferenceType() == T2ReferenceType.IdentifiedList) {
			IdentifiedList<T2Reference> list = saver.getReferenceService()
					.getListService().getList(t2Ref);
			Individual dictionary = provModel.createDictionary(dataURI);
			
			int pos=0;
			for (T2Reference ref : list) {
				URI itemURI = URI.create(uriGenerator.makeT2ReferenceURI(ref.toUri()
						.toASCIIString()));
				Individual listItem = provModel.createArtifact(itemURI);
				provModel.addKeyPair(dictionary, pos++, listItem);
				describeEntity(ref);
			}
			if (list.isEmpty()){
			    artifact.addRDFType(provModel.EmptyCollection);
			    artifact.addRDFType(provModel.EmptyDictionary);
			}
		}

		return artifact;
	}

	private boolean seenReference(T2Reference t2Ref) {
		return seenReferences.containsKey(t2Ref);
	}

    private Path saveIntermediate(T2Reference t2Ref) throws IOException {
        // Avoid double-saving
        Path f = seenReferences.get(t2Ref);
        if (f != null) {
            return f;
        }
        Path file = referencePath(t2Ref);
        
        if (t2Ref.getReferenceType() == T2ReferenceType.IdentifiedList) {
            IdentifiedList<T2Reference> list = saver.getReferenceService()
                    .getListService().getList(t2Ref);
            for (T2Reference ref : list) {
                Path refFile = saveIntermediate(ref).toRealPath();
                // URI relRef =
                // uriTools.relativePath(toURI(parent.toRealPath()),
                // toURI(refFile.toRealPath()));
            }
            seenReference(t2Ref, file);
            return file;
        } else {
            return saveValue(t2Ref, file);
        }

    }

    private Path saveValue(T2Reference t2Ref, Path file) throws IOException {
        Path parent = file.getParent();

        switch (t2Ref.getReferenceType()) {

        case IdentifiedList:
            DataBundles.createList(file);
            IdentifiedList<T2Reference> list = saver.getReferenceService()
                    .getListService().getList(t2Ref);
            long position = 0;
            for (T2Reference ref : list) {
                saveValue(ref, DataBundles.getListItem(file, position++));
            }
            break;
        case ErrorDocument:
            Files.createDirectories(parent);
            file = saveError(t2Ref, file);
            break;
        case ReferenceSet:
            Files.createDirectories(parent);
            file = saver.saveReference(t2Ref, file);
        }
        seenReference(t2Ref, file);
        return file;
    }

    private Path saveError(T2Reference t2Ref, Path file) throws IOException {
        ErrorDocument errorDoc = saver.getReferenceService()
                .getErrorDocumentService().getError(t2Ref);

        StringBuilder trace = new StringBuilder();
        addStackTrace(trace, errorDoc);

        List<Path> causes = new ArrayList<>();
        for (T2Reference cause : errorDoc.getErrorReferences()) {
            causes.add(saveIntermediate(cause));
        }
        file = DataBundles.setError(file, errorDoc.getMessage(),
                trace.toString(), causes.toArray(new Path[causes.size()]));
        return file;
    }	

    protected void addStackTrace(Individual error, ErrorDocument errorDoc)
			throws  IOException {
	    StringBuilder sb = new StringBuilder();
		addStackTrace(sb, errorDoc);
		if (sb.length() > 0) {
		    error.addLiteral(provModel.stackTrace, sb.toString());
		}

		for (T2Reference errRef : errorDoc.getErrorReferences()) {
			URI errorURI = URI.create(uriGenerator.makeT2ReferenceURI(errRef.toUri()
					.toASCIIString()));
			Individual nestedErr = provModel.createError(errorURI);
			provModel.setWasDerivedFrom(error, nestedErr);
			describeEntity(errRef);
		}
	}

	protected void addStackTrace(StringBuilder sb, ErrorDocument errorDoc) {
		if (errorDoc.getExceptionMessage() != null
				&& !errorDoc.getExceptionMessage().isEmpty()) {
			sb.append(errorDoc.getExceptionMessage());
			sb.append("\n");
		}
		if (errorDoc.getStackTraceStrings() == null) {
			return;
		}
		if (sb.length() == 0) {
			sb.append("Stack trace:\n");
		}
		// Attempt to recreate Java stacktrace style
		for (StackTraceElementBean trace : errorDoc.getStackTraceStrings()) {
			sb.append("        at ");
			sb.append(trace.getClassName());
			sb.append(".");
			sb.append(trace.getMethodName());
			sb.append("(");
			sb.append(trace.getFileName());
			sb.append(":");
			sb.append(trace.getLineNumber());
			sb.append(")");
			sb.append("\n");
		}
	}

	protected void addMessageIfNonEmpty(Individual error, String message) {
		if (message == null || message.isEmpty()) {
			return;
		}
		error.addLiteral(provModel.errorMessage, message);
	}

	private Path referencePath(T2Reference t2Ref) throws IOException {
		String local = t2Ref.getLocalPart();
		try {
			return DataBundles.getIntermediate(bundle, UUID.fromString(local));
		} catch (IllegalArgumentException ex) {
		    return DataBundles.getIntermediates(bundle).resolve(t2Ref.getNamespacePart()).resolve(t2Ref.getLocalPart());
		}
		
	}

	private boolean seenReference(T2Reference t2Ref, Path file) {
		getFileToT2Reference().put(file, t2Ref);
		if (seenReference(t2Ref)) {
			return true;
		}
		return seenReferences.put(t2Ref, file) != null;
	}

	public ProvenanceAccess getProvenanceAccess() {
		return provenanceAccess;
	}

	public void setProvenanceAccess(ProvenanceAccess provenanceAccess) {
		this.provenanceAccess = provenanceAccess;
	}

	public String getWorkflowRunId() {
		return workflowRunId;
	}

	public void setWorkflowRunId(String workflowRunId) {
		this.workflowRunId = workflowRunId;
	}

	public void setFileToT2Reference(Map<Path, T2Reference> fileToT2Reference) {
		this.fileToT2Reference = new HashMap<>();
		for (Entry<Path, T2Reference> entry : fileToT2Reference.entrySet()) {
			seenReference(entry.getValue(), entry.getKey());
		}
	}

	private static final String WFDESC = "http://purl.org/wf4ever/wfdesc#";
	private static WorkflowBundleIO wfBundleIO;
    private Bundle bundle;
    private URI runURI;

    /**
     * @return the bundle
     */
    public Bundle getBundle() {
        return bundle;
    }

    public void writeBundle(WorkflowBundle wfBundle) throws IOException  {

        Bundle dataBundle = getBundle();

        // Workflow
        DataBundles.setWorkflowBundle(dataBundle, wfBundle);


        // Generate Manifest
        // TODO: This should be done automatically on close/save
        Manifest manifest = new Manifest(dataBundle);
        manifest.populateFromBundle();
        
        Path workflowRunProvenance = DataBundles.getWorkflowRunProvenance(dataBundle);
        // Additional metadata
        manifest.getAggregation(workflowRunProvenance).setMediatype("text/turtle");
     
        
        Agent taverna = new Agent();
        taverna.setName(applicationConfig.getTitle());
        String versionName = applicationConfig.getName();
        // TODO: is this the correct uri for the software running on this machine??
//        taverna.setUri(URI.create("http://ns.taverna.org.uk/2011/software/"
//                + versionName));
        
        manifest.getAggregation(workflowRunProvenance).setCreatedBy(Arrays.asList(taverna));
        
        // Add annotations
        

        // This RO Bundle is about a run
        PathAnnotation bundleAboutRun = new PathAnnotation();
        bundleAboutRun.setAbout(runURI);
        bundleAboutRun.setContent(URI.create("/"));
        manifest.getAnnotations().add(bundleAboutRun);

        // TODO: Do we need both the "history" link and the annotation below?
        manifest.setHistory(Arrays.asList(workflowRunProvenance));
        
        // This RO Bundle is described in the provenance file
        PathAnnotation provenanceAboutBundle = new PathAnnotation();
        provenanceAboutBundle.setAbout(URI.create("/"));
        provenanceAboutBundle.setContent(URI.create(workflowRunProvenance.toUri().getPath()));
        manifest.getAnnotations().add(provenanceAboutBundle);
        
        // The wfdesc is about the workflow definition 
        Path workflow = DataBundles.getWorkflow(dataBundle);
//            String workflowType = Files.probeContentType(workflow);
        manifest.getAggregation(workflow).setMediatype(WORKFLOW_BUNDLE);
        Path wfdesc = DataBundles.getWorkflowDescription(dataBundle);
        if (Files.exists(wfdesc)) {
            PathAnnotation wfdescAboutWfBundle = new PathAnnotation();
            wfdescAboutWfBundle.setAbout(URI.create(workflow.toUri().getPath()));
            wfdescAboutWfBundle.setContent(URI.create(wfdesc.toUri().getPath()));
            manifest.getAnnotations().add(wfdescAboutWfBundle);
        }
        
        // And the workflow definition is about the workflow
        PathAnnotation wfBundleAboutWf = new PathAnnotation();
        URITools uriTools = new URITools();
        URI mainWorkflow = uriTools.uriForBean(wfBundle.getMainWorkflow());
        wfBundleAboutWf.setAbout(mainWorkflow);
        URI wfBundlePath = URI.create(workflow.toUri().getPath());
        wfBundleAboutWf.setContent(wfBundlePath);
        manifest.getAnnotations().add(wfBundleAboutWf);

        // hasWorkflowDefinition
        PathAnnotation hasWorkflowDefinition = new PathAnnotation();
        hasWorkflowDefinition.setAbout(wfBundlePath);        
        UUID uuid = UUID.randomUUID();
        hasWorkflowDefinition.setAnnotation(URI.create("urn:uuid:" + uuid));
        Path annotationBody = DataBundles.getAnnotations(dataBundle).resolve(uuid + ".ttl");
        hasWorkflowDefinition.setContent(URI.create(annotationBody.toUri().getPath()));
        Model model = ModelFactory.createDefaultModel();
        URI relPathToWfBundle = uriTools.relativePath(annotationBody.toUri(), workflow.toUri());
        model.setNsPrefix("wfdesc", WFDESC);
        model.add(model.createResource(mainWorkflow.toASCIIString()), 
                model.createProperty(WFDESC + "hasWorkflowDefinition"), 
                model.createResource(relPathToWfBundle.toASCIIString()));
        try (OutputStream out = Files.newOutputStream(annotationBody)) {
            model.write(out, "TURTLE", annotationBody.toUri().toASCIIString());
        }
        manifest.getAnnotations().add(hasWorkflowDefinition);
        
        
        PathAnnotation wfBundleAboutWfB = new PathAnnotation();
        wfBundleAboutWfB.setAbout(wfBundle.getGlobalBaseURI());
        wfBundleAboutWfB.setContent(URI.create(workflow.toUri().getPath()));
        manifest.getAnnotations().add(wfBundleAboutWfB);
        
        manifest.writeAsJsonLD();

//        // Saving a data bundle:
//        Path bundleFile = runPath.getParent().resolve(runPath.getFileName() + ".robundle.zip");
//        DataBundles.closeAndSaveBundle(dataBundle, bundleFile);
        // NOTE: From now dataBundle and its Path's are CLOSED
        // and can no longer be accessed

    }

    public void setBundle(Bundle bundle) {
        this.bundle = bundle;
    }

}


