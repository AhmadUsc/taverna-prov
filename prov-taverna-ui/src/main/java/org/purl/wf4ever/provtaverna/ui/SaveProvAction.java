package org.purl.wf4ever.provtaverna.ui;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;

import net.sf.taverna.t2.lang.results.ResultsUtils;
import net.sf.taverna.t2.provenance.api.ProvenanceAccess;
import net.sf.taverna.t2.reference.ErrorDocument;
import net.sf.taverna.t2.reference.ExternalReferenceSPI;
import net.sf.taverna.t2.reference.Identified;
import net.sf.taverna.t2.reference.IdentifiedList;
import net.sf.taverna.t2.reference.ReferenceSet;
import net.sf.taverna.t2.reference.ReferencedDataNature;
import net.sf.taverna.t2.reference.T2Reference;
import net.sf.taverna.t2.workbench.icons.WorkbenchIcons;
import net.sf.taverna.t2.workbench.reference.config.DataManagementConfiguration;
import net.sf.taverna.t2.workbench.views.results.saveactions.SaveAllResultsSPI;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import eu.medsea.mimeutil.MimeType;

public class SaveProvAction extends SaveAllResultsSPI {

	public SaveProvAction() {
		super();
		putValue(NAME, "Save provenance (PROV)");
		putValue(SMALL_ICON, WorkbenchIcons.saveAllIcon);
	}

	ThreadLocal<Map<File, T2Reference>> fileToId = new ThreadLocal<Map<File, T2Reference>>();

	public AbstractAction getAction() {
		return this;
	}

	@Override
	public void setProvenanceEnabledForRun(boolean isProvenanceEnabledForRun) {
		super.setProvenanceEnabledForRun(isProvenanceEnabledForRun);
		setEnabled(isProvenanceEnabledForRun);
	}

	/**
	 * Saves the result data as a file structure
	 * 
	 * @throws IOException
	 */
	protected void saveData(File folder) throws IOException {
		String folderName = folder.getName();
		if (folderName.endsWith(".")) {
			folder = new File(folder.getParentFile(), folderName.substring(0,
					folderName.length()-1));
		}
		fileToId.set(new HashMap<File, T2Reference>());
		try {
			saveToFolder(folder);
		} finally {
			fileToId.remove();
		}

	}

	protected void saveToFolder(File folder) throws IOException,
			FileNotFoundException {
		folder.mkdir();
		if (!folder.isDirectory()) {
			throw new IOException("Could not make/use folder: " + folder);
		}

		// First convert map of references to objects into a map of real result
		// objects
		for (String portName : chosenReferences.keySet()) {
			writeToFileSystem(chosenReferences.get(portName), folder, portName);
		}

		String connectorType = DataManagementConfiguration.getInstance()
				.getConnectorType();
		ProvenanceAccess provenanceAccess = new ProvenanceAccess(connectorType,
				getContext());
		W3ProvenanceExport export = new W3ProvenanceExport(provenanceAccess,
				getRunId());
		export.setFileToT2Reference(fileToId.get());
		File provenanceFile = new File(folder, "workflowrun.prov.ttl");
		BufferedOutputStream outStream = new BufferedOutputStream(
				new FileOutputStream(provenanceFile ));
		try {
			export.exportAsW3Prov(outStream);
		} catch (Exception e) {
			logger.error("Failed to save the provenance graph to "
					+ provenanceFile, e);
			JOptionPane.showMessageDialog(null,
					"Failed to save the provenance graph to " + provenanceFile,
					"Failed to save provenance graph",
					JOptionPane.ERROR_MESSAGE);
		} finally {
			try {
				outStream.close();
			} catch (IOException e) {
			}
		}
	}

	public File writeToFileSystem(T2Reference ref, File destination, String name)
			throws IOException {
		Identified identified = referenceService.resolveIdentifier(ref, null,
				context);

		String fileExtension = "";
		if (identified instanceof ReferenceSet) {

		} else if (identified instanceof ErrorDocument) {
			fileExtension = ".err";
		}

		File writtenFile = writeObjectToFileSystem(destination, name, ref,
				fileExtension);
		return writtenFile;
	}

	/**
	 * Write a specific object to the filesystem this has no access to metadata
	 * about the object and so is not particularly clever. A File object
	 * representing the file or directory that has been written is returned.
	 */
	public File writeObjectToFileSystem(File destination, String name,
			T2Reference ref, String defaultExtension) throws IOException {
		// If the destination is not a directory then set the destination
		// directory to the parent and the name to the filename
		// i.e. if the destination is /tmp/foo.text and this exists
		// then set destination to /tmp/ and name to 'foo.text'
		if (destination.exists() && destination.isFile()) {
			name = destination.getName();
			destination = destination.getParentFile();
		}
		if (destination.exists() == false) {
			// Create the directory structure if not already present
			destination.mkdirs();
		}
		File writtenFile = writeDataObject(destination, name, ref,
				defaultExtension);
		return writtenFile;
	}

	private File writeDataObject(File destination, String name,
			T2Reference ref, String defaultExtension) throws IOException {
		Identified identified = referenceService.resolveIdentifier(ref, null,
				context);

		if (identified instanceof IdentifiedList) {
			// Create a new directory, iterate over the collection recursively
			// calling this method
			File targetDir = new File(destination.toString()
					+ File.separatorChar + name);
			targetDir.mkdir();
			fileToId.get().put(targetDir, identified.getId());
			int count = 0;
			List<T2Reference> elements = referenceService.getListService()
					.getList(ref);
			for (T2Reference subRef : elements) {
				writeDataObject(targetDir, "" + count++, subRef,
						defaultExtension);
			}
			return targetDir;
		}

		else {
			String fileExtension = ".txt";
			if (identified instanceof ReferenceSet) {
				List<MimeType> mimeTypes = new ArrayList<MimeType>();
				ReferenceSet referenceSet = (ReferenceSet) identified;
				List<ExternalReferenceSPI> externalReferences = new ArrayList<ExternalReferenceSPI>(
						referenceSet.getExternalReferences());
				Collections.sort(externalReferences,
						new Comparator<ExternalReferenceSPI>() {
							public int compare(ExternalReferenceSPI o1,
									ExternalReferenceSPI o2) {
								return (int) (o1.getResolutionCost() - o2
										.getResolutionCost());
							}
						});
				for (ExternalReferenceSPI externalReference : externalReferences) {
					if (externalReference.getDataNature().equals(
							ReferencedDataNature.TEXT)) {
						break;
					}
					mimeTypes.addAll(ResultsUtils.getMimeTypes(
							externalReference, context));
				}
				if (!mimeTypes.isEmpty()) {

					// Check for the most interesting type, if defined
					String interestingType = mimeTypes.get(0).toString();

					if (interestingType != null
							&& interestingType.equals("text/plain") == false) {
						// MIME types look like 'foo/bar'
						String lastPart = interestingType.split("/")[1];
						if (lastPart.startsWith("x-") == false) {
							fileExtension = "." + lastPart;
						}
					}
				}
				File targetFile = new File(destination.toString()
						+ File.separatorChar + name + fileExtension);
				IOUtils.copyLarge(
						externalReferences.get(0).openStream(context),
						new FileOutputStream(targetFile));
				fileToId.get().put(targetFile, identified.getId());
				return targetFile;
			} else {
				File targetFile = new File(destination.toString()
						+ File.separatorChar + name + ".err");
				FileUtils.writeStringToFile(targetFile,
						((ErrorDocument) identified).getMessage());
				fileToId.get().put(targetFile, identified.getId());
				return targetFile;
			}

		}
	}

	@Override
	protected String getFilter() {
		return "";
	}

}
