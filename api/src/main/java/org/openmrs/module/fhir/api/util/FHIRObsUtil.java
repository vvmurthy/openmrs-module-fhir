/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.fhir.api.util;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.impl.xb.ltgfmt.Code;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.exceptions.FHIRException;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.EncounterProvider;
import org.openmrs.Obs;
import org.openmrs.Obs.Interpretation;
import org.openmrs.Obs.Status;
import org.openmrs.api.context.Context;
import org.openmrs.obs.ComplexData;
import org.openmrs.util.OpenmrsUtil;
import org.postgresql.util.Base64;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.*;
import java.util.List;

public class FHIRObsUtil {

	private static final Log log = LogFactory.getLog(FHIRObsUtil.class);

	public static Observation generateObs(Obs obs) {

		Observation observation = new Observation();
		//Set observation id
		observation.setId(obs.getUuid());
		//Set issued date
		observation.setIssued(obs.getDateCreated());

		//Set effective date
		DateTimeType type = new DateTimeType();
		type.setValue(obs.getObsDatetime());
		observation.setEffective(type);

		//Set fhir observation comment
		observation.setComment(obs.getComment());
		observation.setSubject(FHIRUtils.buildPatientOrPersonResourceReference(obs.getPerson()));
		//Set fhir performers from openmrs providers
		List<Reference> performers = new ArrayList<Reference>();
		if (obs.getEncounter() != null) {
			for (EncounterProvider provider : obs.getEncounter().getEncounterProviders()) {
				Reference providerReference = new Reference();
				StringBuilder providerNameDisplay = new StringBuilder();
				providerNameDisplay.append(provider.getProvider().getName());
				providerNameDisplay.append("(");
				providerNameDisplay.append(FHIRConstants.IDENTIFIER);
				providerNameDisplay.append(":");
				providerNameDisplay.append(provider.getProvider().getIdentifier());
				providerNameDisplay.append(")");
				providerReference.setDisplay(providerNameDisplay.toString());
				String providerUri = FHIRConstants.PRACTITIONER + "/" + provider.getUuid();
				providerReference.setReference(providerUri);
				performers.add(providerReference);
			}
		}
		observation.setPerformer(performers);

		//Set concepts
		Collection<ConceptMap> mappings = obs.getConcept().getConceptMappings();
		CodeableConcept dt = observation.getCode();
		List<Coding> dts = new ArrayList<Coding>();

		//Set codings from openmrs concept mappings
		for (ConceptMap map : mappings) {
			dts.add(FHIRUtils.getCodingDtByConceptMappings(map));
		}
		//Set openmrs concept
		dts.add(FHIRUtils.getCodingDtByOpenMRSConcept(obs.getConcept()));
		dt.setCoding(dts);

		if (obs.getConcept().isNumeric()) {
			ConceptNumeric cn = Context.getConceptService().getConceptNumeric(obs.getConcept().getId());
			SimpleQuantity quantity = new SimpleQuantity();
			if(obs.getValueNumeric() != null) {
				quantity.setValue(obs.getValueNumeric());
				quantity.setSystem(FHIRConstants.NUMERIC_CONCEPT_MEASURE_URI);
				quantity.setUnit(cn.getUnits());
				quantity.setCode(cn.getUnits());
				observation.setValue(quantity);
			}
			//Set high and low ranges
			List<Observation.ObservationReferenceRangeComponent> referenceRanges = new ArrayList<Observation.ObservationReferenceRangeComponent>();
			Observation.ObservationReferenceRangeComponent referenceRange = new Observation.ObservationReferenceRangeComponent();
			if (cn.getHiAbsolute() != null) {
				SimpleQuantity high = new SimpleQuantity();
				high.setUnit(cn.getUnits());
				high.setCode(cn.getUnits());
				high.setSystem(FHIRConstants.NUMERIC_CONCEPT_MEASURE_URI);
				high.setValue(cn.getHiAbsolute());
				referenceRange.setHigh(high);
			}
			if (cn.getLowAbsolute() != null) {
				SimpleQuantity low = new SimpleQuantity();
				low.setUnit(cn.getUnits());
				low.setCode(cn.getUnits());
				low.setSystem(FHIRConstants.NUMERIC_CONCEPT_MEASURE_URI);
				low.setValue(cn.getLowAbsolute());
				referenceRange.setLow(low);
			}
			referenceRanges.add(referenceRange);
			observation.setReferenceRange(referenceRanges);

		} else if (FHIRConstants.ST_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			StringType value = new StringType();
			value.setValue(obs.getValueAsString(Context.getLocale()));
			observation.setValue(value);
			
		} else if (FHIRConstants.BIT_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			CodeableConcept codeableConceptDt = new CodeableConcept();
			List<Coding> codingDts = new ArrayList<Coding>();
			Coding codingDt = new Coding();
			codingDt.setCode(obs.getValueAsBoolean().toString()); // fixed by sashrika
			codingDts.add(codingDt);
			codeableConceptDt.setCoding(codingDts);
			observation.setValue(codeableConceptDt);
		} else if (FHIRConstants.TS_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			Period datetime = new Period();
			datetime.setStart(obs.getValueDatetime());
			datetime.setEnd(obs.getValueDatetime());
			observation.setValue(datetime);

		} else if (FHIRConstants.DT_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			Period datetime = new Period();
			datetime.setStart(obs.getValueDate());
			datetime.setEnd(obs.getValueDate());
			observation.setValue(datetime);
			
		} else if (FHIRConstants.CWE_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			if (obs.getValueCoded() != null) {
				Collection<ConceptMap> valueMappings = obs.getValueCoded().getConceptMappings();
				List<Coding> values = new ArrayList<Coding>();
				//Set codings from openmrs concept mappings
				for (ConceptMap map : valueMappings) {
					if (map.getConceptReferenceTerm() != null) {
						values.add(FHIRUtils.getCodingDtByConceptMappings(map));
					}
				}
				//Set openmrs concept
				values.add(FHIRUtils.getCodingDtByOpenMRSConcept(obs.getValueCoded()));
				CodeableConcept codeableConceptDt = new CodeableConcept();
				codeableConceptDt.setCoding(values);
				observation.setValue(codeableConceptDt);
			}
		} else if (FHIRConstants.ED_HL7_ABBREVATION.equalsIgnoreCase(obs.getConcept().getDatatype().getHl7Abbreviation())) {
			Attachment attachmentDt = new Attachment();
			attachmentDt.setUrl(FHIRConstants.COMPLEX_DATA_URL + obs.getId());

			// READ IMAGE FROM LOCAL
            StringBuilder values = new StringBuilder();
            BufferedImage image;
            try{
                image = ImageIO.read(new File(obs.getValueComplex()));
            }catch (IOException e){
                throw new UnprocessableEntityException("Cannot load image");
            }

            /*
            values.append(image.getHeight());
            values.append(" ");
            values.append(image.getWidth());
            values.append(" ");

            for(int i = 0 ; i < image.getHeight() ; i++){
                for(int j = 0 ; j < image.getWidth() ; j++){
                    Color color = new Color(image.getRGB(j, i));

                    if(color.getRed() != color.getBlue() || color.getRed() != color.getGreen()){
                        throw new UnprocessableEntityException("Grayscale images only are supported");
                    }

                    values.append(color.getRed());
                    values.append(" ");
                }
            }
            attachmentDt.setTitle(values.toString());
			observation.setValue(attachmentDt);
			*/

            byte[] valuesI = new byte[image.getWidth() * image.getHeight()];

			for(int i = 0 ; i < image.getHeight() ; i++){
				for(int j = 0 ; j < image.getWidth() ; j++){
					Color color = new Color(image.getRGB(j, i));

					if(color.getRed() != color.getBlue() || color.getRed() != color.getGreen()){
						throw new UnprocessableEntityException("Grayscale images only are supported");
					}

					byte value = (byte)(color.getRed() - 128);
					valuesI[i * image.getWidth() + j] = value;
				}
			}
			Base64BinaryType bt = new Base64BinaryType();
			bt.setValue(valuesI);
			attachmentDt.setSize(image.getHeight() * 10000 + image.getWidth());


			// Bounds check for integer representation of width;
			// If image width > 10000 it will not be represented correctly in FHIR format
			// hence this check
			if(image.getWidth() >= 10000){
				throw new UnprocessableEntityException("image width is >= 100000 pixels, please use lower resolution" +
						"images only");
			}

			attachmentDt.setDataElement(bt);
			observation.setValue(attachmentDt);

		} else {
			StringType value = new StringType();
			value.setValue(obs.getValueAsString(Context.getLocale()));
			observation.setValue(value);
		}

		
		CodeableConcept interpretation = null;
		Observation.ObservationStatus status = Observation.ObservationStatus.FINAL;
		try {
			Status stat = obs.getStatus();
			if (stat != null) {
				status = Observation.ObservationStatus.valueOf(stat.name());
			}
			
			Interpretation interpret = obs.getInterpretation();
			if (interpret != null) {
				interpretation = new CodeableConcept();
				interpretation.setText(interpret.name());
			}
		}
		catch (NoSuchMethodError ex) {
			//must be running below platform 2.1
		}
		
		observation.setStatus(status);
		observation.setInterpretation(interpretation);
		observation.setIssued(obs.getObsDatetime());

		//Set reference observations
		List<Observation.ObservationRelatedComponent> relatedObs = null;
		if (obs.getGroupMembers() != null && !obs.getGroupMembers().isEmpty()) {
			relatedObs = new ArrayList<Observation.ObservationRelatedComponent>();
			Reference resourceReferenceDt;
			Observation.ObservationRelatedComponent related;
			for (Obs ob : obs.getGroupMembers()) {
				related = new Observation.ObservationRelatedComponent();
				related.setType(Observation.ObservationRelationshipType.HASMEMBER);
				resourceReferenceDt = new Reference();
				resourceReferenceDt.setDisplay(ob.getConcept().getName().getName());
				String obsUri = FHIRConstants.OBSERVATION + "/" + obs.getUuid();
				resourceReferenceDt.setReference(obsUri);
				related.setTarget(resourceReferenceDt);
				relatedObs.add(related);
			}
		}
		//Set old Obs
		if (obs.getPreviousVersion() != null) {
			if (relatedObs == null) {
				relatedObs = new ArrayList<Observation.ObservationRelatedComponent>();
			}

			Reference resourceReferenceDt = new Reference();
			Observation.ObservationRelatedComponent related = new Observation.ObservationRelatedComponent();
			related.setType(Observation.ObservationRelationshipType.REPLACES);
			resourceReferenceDt.setDisplay("Old Obs which replaced by the new Obs");
			String obsUri = FHIRConstants.OBSERVATION + "/" + obs.getPreviousVersion().getUuid();
			resourceReferenceDt.setReference(obsUri);
			related.setTarget(resourceReferenceDt);
			relatedObs.add(related);
		}
		observation.setRelated(relatedObs);
		return observation;
	}

	/**
	 * Method to generate observation with encounter
	 * @param observation fhir observation
	 * @param encounter encounter to link
	 * @param errors error list
	 * @return created observation
	*/
	public static Obs generateOpenMRSObsWithEncounter(Observation observation, Encounter encounter, List<String> errors) {
		Obs createdObs = generateOpenMRSObs(observation, errors);
		createdObs.setEncounter(encounter);
		return createdObs;
	}

	public static Obs generateOpenMRSObs(Observation observation, List<String> errors) {
		Obs obs = new Obs();
		obs.setComment(observation.getComment());
		if (observation.getSubject() != null) {
			Reference subjectref = observation.getSubject();
			String patientUuid = subjectref.getId();
			org.openmrs.Person person = Context.getPersonService().getPersonByUuid(patientUuid);
			if (person == null) {
				errors.add("There is no person for the given uuid");
			} else {
				obs.setPerson(person);
			}
		} else {
			errors.add("Subject cannot be empty");
		}

		Date dateCreated = observation.getIssued();
		if (dateCreated == null) {
			obs.setDateCreated(new Date());
		} else {
			obs.setDateCreated(dateCreated);
		}

		Date dateEffective = null;
		if(observation.getEffective() instanceof DateTimeType) {
			dateEffective = ((DateTimeType) observation.getEffective()).getValue();
			if (dateEffective == null) {
				errors.add("Observation DateTime cannot be empty");
			} else {
				obs.setObsDatetime(dateEffective);
			}
		} else if (observation.getEffective() instanceof Period) {
			dateEffective = ((Period) observation.getEffective()).getStart();
			if (dateEffective == null) {
				errors.add("Observation DateTime cannot be empty");
			} else {
				obs.setObsDatetime(dateEffective);
			}
		} else {
			errors.add("Observation DateTime cannot be empty");
		}

		// Link Encounter / Location through extension
        // I have added this section to keep the FHIR module consistent with the docs
        if(observation.getExtension() != null){
		    for(Extension extension : observation.getExtension()){
		        if(extension.getUrl().equalsIgnoreCase(FHIRConstants.LOCATION_EXTENTION_URI)){
		            org.openmrs.Location data = Context.getLocationService().getLocationByUuid(extension.getId());
		            if(data == null){
		                errors.add("Tried to parse location; location ID does not exist");
                    }
                    obs.setLocation(data);

                }else if(extension.getUrl().equalsIgnoreCase(FHIRConstants.ENCOUNTER_EXTENTION_URI)){
		            Encounter data = Context.getEncounterService().getEncounterByUuid(extension.getId());
		            if(data == null){
		                errors.add("Tried to parse encounter; Encounter ID does not exist");
                    }
                    obs.setEncounter(data);
                }
            }
        }
		
		String conceptCode = null;
		String system = null;
		Concept concept = null;
		List<Coding> dts = null;
		try {
			CodeableConcept dt = observation.getCode();
			dts = dt.getCoding();
		}
		catch (NullPointerException e) {
			errors.add("Code cannot be empty");
			log.error("Code cannot be empty " + e.getMessage());
		}
		
		for (Coding cding : dts) {
			conceptCode = cding.getCode();
			system = cding.getSystem();
			if (FHIRConstants.OPENMRS_URI.equals(system)) {
				concept = Context.getConceptService().getConceptByUuid(conceptCode);
			} else {
				String systemName = FHIRConstants.conceptSourceURINameMap.get(system);
				if (systemName != null && !systemName.isEmpty()) {
					concept = Context.getConceptService().getConceptByMapping(conceptCode, systemName);
				}
			}
			if (concept != null) {
				break;
			}
		}
		if (concept == null) {
			errors.add("No matching concept found for the given codings");
		} else {
			obs.setConcept(concept);
		}

		if (concept != null) {
			if (observation.getValue() == null) {
				errors.add("Obs set value cannot be empty");
			} else {
				if (concept.isNumeric()) {
					Quantity quantity = (Quantity) observation.getValue();
					BigDecimal bd = quantity.getValue();
					double doubleValue = bd.doubleValue();

					if(doubleValue % 1 != 0 && !((ConceptNumeric)concept).getAllowDecimal() ){
						((ConceptNumeric)concept).setAllowDecimal(true);
						Context.getConceptService().saveConcept(concept);
					}

					obs.setValueNumeric(doubleValue);
				} else if (FHIRConstants.ST_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					StringType value = (StringType) observation.getValue();
					try {
						obs.setValueAsString(value.getValue());
					}
					catch (ParseException e) {
						errors.add("Obs set value failed");
						log.error("Obs set value failed " + e.getMessage());
					}
				} else if (FHIRConstants.BIT_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					CodeableConcept codeableConceptDt = (CodeableConcept) observation.getValue();
					try {
						List<Coding> codingDts = codeableConceptDt.getCoding();
						Coding codingDt2 = codingDts.get(0);
						boolean booleanValue = Boolean.parseBoolean(codingDt2.getCode());
						obs.setValueBoolean(booleanValue);
					}
					catch (NullPointerException e) {
						errors.add("Setting valueBoolean failed");
						log.error("Setting valueBoolean failed " + e.getMessage());
					}
				} else if (FHIRConstants.TS_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					Period datetime = (Period) observation.getValue();
					obs.setValueDatetime(datetime.getStart());
					
				} else if (FHIRConstants.DT_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					Period datetime = (Period) observation.getValue();
					obs.setValueDate(datetime.getStart());
				} else if (FHIRConstants.ED_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					Attachment attachmentDt = (Attachment) observation.getValue();
					String elements = attachmentDt.getDataElement().getValueAsString();
					String[] values = elements.split(" ");

					Integer height = Integer.parseInt(values[0]);
					Integer width = Integer.parseInt(values[1]);

					if(values.length != height * width + 2){
					    errors.add("Invalid image data sent: must be in format [height] [width] [row major pixel data]");
                    }

                    int n = 2;
                    BufferedImage data = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
					for(int i = 0 ; i < data.getHeight() ; i++){
					    for(int j = 0 ; j < data.getWidth() ; j++){
					        Color color;

					        if(values.length == height * width + 2){
					            Integer grayValue = Integer.parseInt(values[n]);
					            n++;
					            color = new Color(grayValue, grayValue, grayValue);
                            }else{
					            Integer red = Integer.parseInt(values[n]);
					            Integer green = Integer.parseInt(values[n + 1]);
					            Integer blue = Integer.parseInt(values[n + 2]);
					            n += 3;
					            color = new Color(red, green, blue);
                            }
                            data.setRGB(j, i, color.getRGB());
                        }
                    }

                    // Save imaging data to local folder
                    // NOTE: Must have a subfolder named data where your openmrs instance is located
                    try{
                        ImageIO.write(data, "png", new File(
                                OpenmrsUtil.getApplicationDataDirectory() + "data" + File.separator + obs.getUuid() + ".png"));
                    }catch (IOException e){
					    errors.add("Could not save file");
                    }

					obs.setValueComplex(OpenmrsUtil.getApplicationDataDirectory() + "data" + File.separator + obs.getUuid() + ".png");
				}else if(FHIRConstants.CWE_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())){
                    CodeableConcept data = (CodeableConcept)observation.getValue();

                    for(Coding evaluate : data.getCoding()){
                        if(evaluate.getSystem().equalsIgnoreCase(FHIRConstants.OPENMRS_URI)){
                            Concept value = Context.getConceptService().getConceptByUuid(evaluate.getCode());
                            obs.setValueCoded(value);
                        }
                    }

				}
			}
		}
		
		CodeableConcept interpretation = observation.getInterpretation();
		Observation.ObservationStatus status = observation.getStatus();
		
		try {
			if (status != null) {
				obs.setStatus(Status.valueOf(status.name()));
			}
			
			if (interpretation != null && StringUtils.isNotBlank(interpretation.getText())) {
				obs.setInterpretation(Interpretation.valueOf(interpretation.getText()));
			}
		}
		catch (NoSuchMethodError ex) {
			//must be running below platform 2.1
		}
		
		return obs;
	}
	
	public static Obs copyObsAttributes(Obs requestObs, Obs retrievedObs, List<String> errors) {
		retrievedObs.setPerson(requestObs.getPerson());
		retrievedObs.setObsDatetime(requestObs.getObsDatetime());
		retrievedObs.setConcept(requestObs.getConcept());
		Concept concept = requestObs.getConcept(); // potential bug here. if we update the concept, we should check whether the existing value obs value datatype is match. 
		if (concept != null) { // potential bug here. even the concept is null, we should allow update obs value
			if (requestObs.getConcept().isNumeric()) {
				retrievedObs.setValueNumeric(requestObs.getValueNumeric());
			} else if (FHIRConstants.ST_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
				try {
					retrievedObs.setValueAsString(requestObs.getValueAsString(Context.getLocale()));
				}
				catch (ParseException e) {
					errors.add("Couldn't set value as String to the Observation. Caused " + e.getMessage());
				}
			} else if (FHIRConstants.BIT_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
				retrievedObs.setValueCoded(requestObs.getValueCoded());
			} else if (FHIRConstants.TS_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
					retrievedObs.setValueDatetime(requestObs.getValueDatetime());
			} else if (FHIRConstants.DT_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
				retrievedObs.setValueDate(requestObs.getValueDate());
			} else if (FHIRConstants.ED_HL7_ABBREVATION.equalsIgnoreCase(concept.getDatatype().getHl7Abbreviation())) {
				//TBD
			}
	    }
		retrievedObs.setComment(requestObs.getComment());
		return retrievedObs;
	}

	/**
	 * Build FhIRe reference from Encounter
	 * @param encounter encounter resource
	 * @return FHIR Reference
     */
	public static Reference getFHIREncounterReference(Encounter encounter) {
		Reference encounterRef = new Reference();
		String encounterUri = FHIRConstants.ENCOUNTER + "/" + encounter.getUuid();
		encounterRef.setReference(encounterUri);
		return encounterRef;
	}


	public static boolean compareCurrentObs(Object observation1, Object observation2) {
		Obs p1 = (Obs) observation1;
		Obs p2 = (Obs) observation2;


		if (p1.getAccessionNumber() != null && p2.getAccessionNumber() != null && !(p1.getAccessionNumber().equals(p2.getAccessionNumber()))) {
			return false;
		} else if (p1.getComment() != null && !(p1.getComment().equals(p2.getComment()))) {
			return false;
		} else if (null != p1.getConcept() && !p1.getConcept().equals(p2.getConcept())) {
			return false;
		} else if (null != p1.getLocation() && !p1.getLocation().equals(p2.getLocation())) {
			return false;
		} else if (p1.getEncounter() != null && !(p1.getEncounter().equals(p2.getEncounter()))) {
			return false;
		} else if (null != p1.getValueNumeric() && !p1.getValueNumeric().equals(p2.getValueNumeric())) {
			return false;
		} else if (null != p1.getObsDatetime() && !p1.getObsDatetime().equals(p2.getObsDatetime())) {
			return false;
		} else if (null != p1.getOrder() && !p1.getOrder().equals(p2.getOrder())) {
			return false;
		} else if (null != p1.getPerson() && !p1.getPerson().equals(p2.getPerson())) {
			return false;
		} else if (null != p1.getStatus() && !p1.getStatus().equals(p2.getStatus())) {
			return false;
		} else {
			return p1.getVoided() == p2.getVoided();
		}

	}
}
