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

import org.apache.commons.lang.StringUtils;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.dstu3.model.Encounter;
import org.hl7.fhir.exceptions.FHIRException;
import org.openmrs.*;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FHIREncounterUtil {

	public static Composition generateComposition(org.openmrs.Encounter omrsEncounter) throws FHIRException {

		Composition composition = new Composition();

		//Set id of the composition from omrs encounter id
		IdType uuid = new IdType();
		uuid.setValue(omrsEncounter.getUuid());
		composition.setId(uuid);

		//Set composition date
		composition.setDate(omrsEncounter.getEncounterDatetime());

		//Set composition status
		composition.setStatus(Composition.CompositionStatus.FINAL);

		//Set document confidentiality
		Composition.DocumentConfidentiality confidentialityCode = Composition.DocumentConfidentiality.
																		fromCode(FHIRConstants.CONFIDENTIALITY_CODING_R);
		composition.setConfidentiality(confidentialityCode);

		//Set composition subject as patient resource
		composition.setSubject(buildPatientReference(omrsEncounter));

		//Setting composition author
		if (omrsEncounter.getEncounterProviders().size() > 0) {
			List<Reference> authors = new ArrayList<Reference>();
			Reference author;
			for (EncounterProvider provider : omrsEncounter.getEncounterProviders()) {
				author = new Reference();
				StringBuilder providerNameDisplay = new StringBuilder();
				providerNameDisplay.append(provider.getProvider().getName());
				providerNameDisplay.append("(");
				providerNameDisplay.append(FHIRConstants.IDENTIFIER);
				providerNameDisplay.append(":");
				providerNameDisplay.append(provider.getProvider().getIdentifier());
				providerNameDisplay.append(")");
				author.setDisplay(providerNameDisplay.toString());
				IdType providerRef = new IdType();
				String providerUri = FHIRConstants.PRACTITIONER + "/" + provider.getProvider().getUuid();
				providerRef.setValue(providerUri);
				author.setReference(providerUri);
				authors.add(author);
			}
			composition.setAuthor(authors);
		}

		//Set encounter reference
		Reference encounterRef = new Reference();
		String encounterRefId = FHIRConstants.ENCOUNTER + "/" + omrsEncounter.getUuid();
		encounterRef.setReference(encounterRefId);
		composition.setEncounter(encounterRef);

		//Set location
		Composition.SectionComponent locationSection = composition.addSection();
		Reference locationRef = locationSection.addEntry();
		locationRef.setDisplay(FHIRConstants.LOCATION);
		String locationUri = FHIRConstants.LOCATION + "/" + omrsEncounter.getLocation().getUuid();
		locationRef.setReference(locationUri);

		//Set observation section
		if (omrsEncounter.getAllObs(false).size() > 0) {
			Composition.SectionComponent obsSection = composition.addSection();
			Reference obsRef;
			String obsUri;
			for (Obs obs : omrsEncounter.getAllObs(false)) {
				obsRef = obsSection.addEntry();
				obsUri = FHIRConstants.OBSERVATION + "/" + obs.getUuid();
				obsRef.setReference(obsUri);
			}
		}
		return composition;
	}

	public static Encounter generateEncounter(org.openmrs.Encounter omrsEncounter) {
		Encounter encounter = new Encounter();

		IdType uuid = new IdType();
		uuid.setValue(omrsEncounter.getUuid());
		encounter.setId(uuid);
		encounter.setStatus(Encounter.EncounterStatus.FINISHED);

		//Set patient reference
		encounter.setSubject(buildPatientReference(omrsEncounter));

		//Set participants
		if (omrsEncounter.getEncounterProviders().size() > 0) {
			List<Encounter.EncounterParticipantComponent> participants = new ArrayList<Encounter.EncounterParticipantComponent>();
			Encounter.EncounterParticipantComponent participant;
			for (EncounterProvider provider : omrsEncounter.getEncounterProviders()) {
				participant = new Encounter.EncounterParticipantComponent();
				Reference providerReference = new Reference();
				StringBuilder providerNameDisplay = new StringBuilder();
				if (provider.getProvider() != null) {
					providerNameDisplay.append(provider.getProvider().getName());
					providerNameDisplay.append("(");
					providerNameDisplay.append(FHIRConstants.IDENTIFIER);
					providerNameDisplay.append(":");
					providerNameDisplay.append(provider.getProvider().getIdentifier());
					providerNameDisplay.append(")");
					providerReference.setDisplay(providerNameDisplay.toString());
				}

				String providerUri = FHIRConstants.PRACTITIONER + "/" + provider.getUuid();
				providerReference.setReference(providerUri);
				participant.setIndividual(providerReference);
				participants.add(participant);
			}
			encounter.setParticipant(participants);
		}
		//Set encounter period from omrs encounter
		Period period = encounter.getPeriod();
		period.setStart(omrsEncounter.getEncounterDatetime());
		period.setEnd(omrsEncounter.getEncounterDatetime());
		encounter.setPeriod(period);

		//Set encounter location from omrs location
		if (omrsEncounter.getLocation() != null) {
			List<Encounter.EncounterLocationComponent> locations = new ArrayList<Encounter.EncounterLocationComponent>();
			Encounter.EncounterLocationComponent location = new Encounter.EncounterLocationComponent();
			//set encounter period
			location.setPeriod(period);
			Reference locationReference = new Reference();
			locationReference.setDisplay(omrsEncounter.getLocation().getName());
			String locationRefUri = FHIRConstants.LOCATION + "/" + omrsEncounter.getLocation().getUuid();
			locationReference.setReference(locationRefUri);
			location.setLocation(locationReference);
			locations.add(location);
			encounter.setLocation(locations);
		}

		if (omrsEncounter.getVisit() != null) {
			//Set visit resource as a part of a encounter
			Reference visitRef = new Reference();
			visitRef.setDisplay(omrsEncounter.getVisit().getVisitType().getName());
			String visitRefUri = FHIRConstants.ENCOUNTER + "/" + omrsEncounter.getVisit().getUuid();
			visitRef.setReference(visitRefUri);
			encounter.setPartOf(visitRef);
		}

		String encounterType = omrsEncounter.getEncounterType().getName();
		Coding dt = new Coding();
		dt.setDisplay(encounterType);
		encounter.getTypeFirstRep().getCoding().add(dt);

		//TODO uncomment the validation and check what's going wrong
		//FHIRUtils.validate(encounter);
		return encounter;
	}

	private static Reference buildPatientReference(org.openmrs.Encounter omrsEncounter) {
		//Build and set patient reference
		Reference patientReference = new Reference();
		PersonName name = omrsEncounter.getPatient().getPersonName();
		StringBuilder nameDisplay = new StringBuilder();
		nameDisplay.append(name.getGivenName());
		nameDisplay.append(" ");
		nameDisplay.append(name.getFamilyName());
		String patientUri;
		nameDisplay.append("(");
		nameDisplay.append(FHIRConstants.IDENTIFIER);
		nameDisplay.append(":");
		nameDisplay.append(omrsEncounter.getPatient().getPatientIdentifier().getIdentifier());
		nameDisplay.append(")");
		patientUri = FHIRConstants.PATIENT + "/" + omrsEncounter.getPatient().getUuid();
		patientReference.setReference(patientUri);
		patientReference.setDisplay(nameDisplay.toString());
		patientReference.setId(omrsEncounter.getPatient().getUuid());
		return patientReference;
	}

	/**
	 * Filter which obs need to be added to the encounter everything operation. Because some openmrs
	 * installations store allergies as obs. In that case we need to omit obs getting included in
	 * everything operation and return them in patient everything operation
	 *
	 * @param encounter encounter containing obs
	 * @param bundle bundle containg encounter everything contents
	 * @return bundle with only required obs
	 */
	public static void addFilteredObs(org.openmrs.Encounter encounter, Bundle bundle) {
		String strategy = FHIRUtils.getAllergyStrategy();
		Bundle.BundleEntryComponent observation;
		if (FHIRConstants.OBS_ALLERGY_STRATEGY.equals(strategy)) {
			String allergyCode = FHIRUtils.getObsAllergyStrategyConceptUuid();
			Concept concept = Context.getConceptService().getConceptByUuid(allergyCode);
			for (Obs obs : encounter.getAllObs(false)) {
				if (concept != null && !concept.equals(obs.getConcept())) {
					observation = bundle.addEntry();
					observation.setResource(FHIRObsUtil.generateObs(obs));
				}
			}
		} else {
			for (Obs obs : encounter.getAllObs(false)) {
				observation = bundle.addEntry();
				observation.setResource(FHIRObsUtil.generateObs(obs));
			}
		}
	}
	
	public static org.openmrs.Encounter generateOMRSEncounter(Encounter encounter, List<String> errors) {
		org.openmrs.Encounter omrsEncounter = new org.openmrs.Encounter();
		if (encounter.getSubject() != null) {
			Reference patientref = encounter.getSubject();
			String patientUuid = patientref.getId();
			org.openmrs.Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
			if (patient == null) {
				errors.add("There is no patient for the given uuid " + patientUuid); // remove to constants
			} else {
				omrsEncounter.setPatient(patient);
			}
		}
		Period period = encounter.getPeriod();
		Date start = period.getStart();
		omrsEncounter.setEncounterDatetime(start);
		
		//
		
		List<Encounter.EncounterParticipantComponent> participants = encounter.getParticipant();
		for (Encounter.EncounterParticipantComponent participant : participants) {
			Reference participantsref = participant.getIndividual();
			String participantUuid = participantsref.getId();

			if(StringUtils.isEmpty(participantUuid) && participantsref != null) {
				participantUuid = participantsref.getId();
			}

			if(StringUtils.isEmpty(participantUuid)) {
				String participantRefStr = participantsref.getReference();
				String[] participantRefStrSplit = participantRefStr.split("/");
				if(participantRefStrSplit.length > 1) {
					participantUuid = participantRefStrSplit[1];
				}
			}

			org.openmrs.Provider provider = Context.getProviderService().getProviderByUuid(participantUuid);
			EncounterRole role = Context.getEncounterService().getEncounterRole(1); // hard coded
			if(provider != null){
				omrsEncounter.setProvider(role, provider);
			}
		}
		List<Encounter.EncounterLocationComponent> locationList = encounter.getLocation();
		if (locationList != null && !locationList.isEmpty()) {
			Encounter.EncounterLocationComponent location = locationList.get(0);
			Reference locationref = location.getLocation();

			String locationUuid = locationref.getId();
			if(StringUtils.isEmpty(locationUuid) && locationref != null) {
				locationUuid = locationref.getId();
			}

			if(StringUtils.isEmpty(locationUuid)) {
				String locationRefStr = locationref.getReference();
				String[] locationRefStrSplit = locationRefStr.split("/");
				if(locationRefStrSplit.length > 1) {
					locationUuid =locationRefStrSplit[1];
				}
			}

			org.openmrs.Location omrsLocation = Context.getLocationService().getLocationByUuid(locationUuid);
			if (omrsLocation != null) {
				omrsEncounter.setLocation(omrsLocation);
			}
		}

		String encounterTypeName = encounter.getTypeFirstRep().getCodingFirstRep().getDisplay();
		EncounterType encounterType = Context.getEncounterService().getEncounterType(encounterTypeName);

		// Provide a secondary option in case display name is not provided
        // Encounter type can be linked by uuid as well.
        if(encounterType == null){
            String encounterTypeUuid = encounter.getTypeFirstRep().getCodingFirstRep().getCode();
            encounterType = Context.getEncounterService().getEncounterTypeByUuid(encounterTypeUuid);
        }

		omrsEncounter.setEncounterType(encounterType);

		return omrsEncounter;
	}
	
	public static org.openmrs.Visit generateOMRSVisit(Encounter encounter, List<String> errors) {
		org.openmrs.Visit visit = new org.openmrs.Visit();
		if (encounter.getSubject() != null) {
			Reference patientref = encounter.getSubject();
			String patientUuid = patientref.getId();
			org.openmrs.Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
			if (patient == null) {
				errors.add("There is no patient for the given uuid " + patientUuid); // remove to constants
			} else {
				visit.setPatient(patient);
			}
		}
		List<CodeableConcept> types = encounter.getType();
		for (CodeableConcept type : types) {
			List<Coding> typeCodings = type.getCoding();
			VisitType visitType = null;
			if (typeCodings != null && !typeCodings.isEmpty()) {
				Coding code = typeCodings.get(0);
				String typeCode = code.getCode();
				visitType = Context.getVisitService().getVisitTypeByUuid(typeCode);
				//int typeId = Integer.parseInt(typeCode);
				//visitType = Context.getVisitService().getVisitType(typeId);
			}

			if (visitType == null) {
				errors.add("There is no Visit Type for the given type id");
			}
			visit.setVisitType(visitType);
		}
		
		Period period = encounter.getPeriod();
		Date start = period.getStart();
		if (start == null) {
			errors.add("Start date cannot be empty");
		}
		visit.setStartDatetime(start);
		visit.setStopDatetime(period.getEnd());
		return visit;
	}

	public static org.openmrs.Encounter updateEncounterAttributes(org.openmrs.Encounter omrsEncounter, org.openmrs.Encounter retrievedEncounter) {
		retrievedEncounter.setEncounterDatetime(omrsEncounter.getEncounterDatetime());
		retrievedEncounter.setEncounterType(omrsEncounter.getEncounterType());
		retrievedEncounter.setLocation(omrsEncounter.getLocation());
		retrievedEncounter.setPatient(omrsEncounter.getPatient());
		retrievedEncounter.setDateCreated(omrsEncounter.getDateCreated());
		retrievedEncounter.setEncounterProviders(omrsEncounter.getEncounterProviders());
		retrievedEncounter.setForm(omrsEncounter.getForm());
		retrievedEncounter.setObs(omrsEncounter.getObs());
		retrievedEncounter.setOrders(omrsEncounter.getOrders());
		retrievedEncounter.setPatient(omrsEncounter.getPatient());
		retrievedEncounter.setVisit(omrsEncounter.getVisit());
		return retrievedEncounter;
	}

	public static boolean compareCurrentEncounters(Object encounter1, Object encounter2) {
		org.openmrs.Encounter p1 = (org.openmrs.Encounter)encounter1;
		org.openmrs.Encounter p2 = (org.openmrs.Encounter)encounter2;
		if(p1.getObs().size() == p2.getObs().size()) {
			for(int i = 0; i < p1.getObs().size(); ++i) {
				if(!(p1.getObs().toArray()[i]).equals(p2.getObs().toArray()[i])) {
					return false;
				}
			}


		} else if (p1.getOrders().size() == p2.getOrders().size()) {
			for (int i = 0; i < p1.getOrders().size(); ++i) {
				if (!(p1.getOrders().toArray()[i]).equals(p2.getOrders().toArray()[i])) {
					return false;
				}
			}
		} else if (p1.getEncounterProviders().size() == p2.getEncounterProviders().size()) {
			for (int i = 0; i < p1.getEncounterProviders().size(); ++i) {
				if (!(p1.getEncounterProviders().toArray()[i]).equals(p2.getEncounterProviders().toArray()[i])) {
					return false;
				}
			}
		} else {
			if(p1.getDateChanged() != null && p2.getDateChanged() != null && !(p1.getDateChanged().equals(p2.getDateChanged()))) {
				return false;
			} else if(p1.getDateCreated() != null && !(p1.getDateCreated().equals(p2.getDateCreated()))) {
				return false;
			} else if(null != p1.getEncounterType() && !p1.getEncounterType().equals(p2.getEncounterType())) {
				return false;
			} else if(null != p1.getLocation() && !p1.getLocation().equals(p2.getLocation())) {
				return false;
			} else if(p1.getPatient() != null && !(p1.getPatient().equals(p2.getPatient()))) {
				return false;
			} else if(null != p1.getForm() && !p1.getForm().equals(p2.getForm())) {
				return false;
			} else if(null != p1.getVisit() && !p1.getVisit().equals(p2.getVisit())) {
				return false;
			} else if(null != p1.getEncounterDatetime() && p1.getEncounterDatetime().equals(p2.getEncounterDatetime())) {
				return false;
			}
			else {
				return p1.getVoided() == p2.getVoided();
			}
		}
		return false;
	}
}
