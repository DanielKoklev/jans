/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2020, Gluu
 */

package org.gluu.oxauth.fido2.service.verifier;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.gluu.oxauth.fido2.ctap.AttestationConveyancePreference;
import org.gluu.oxauth.fido2.ctap.AuthenticatorAttachment;
import org.gluu.oxauth.fido2.ctap.TokenBindingSupport;
import org.gluu.oxauth.fido2.ctap.UserVerification;
import org.gluu.oxauth.fido2.exception.Fido2RPRuntimeException;
import org.gluu.oxauth.fido2.model.auth.AuthData;
import org.gluu.oxauth.fido2.model.auth.CredAndCounterData;
import org.gluu.oxauth.fido2.service.Base64Service;
import org.gluu.oxauth.fido2.service.DataMapperService;
import org.gluu.oxauth.fido2.service.processors.AttestationFormatProcessor;
import org.gluu.oxauth.fido2.service.server.AppConfiguration;
import org.gluu.service.net.NetworkService;
import org.gluu.util.StringHelper;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author Yuriy Movchan
 * @version May 08, 2020
 */
@ApplicationScoped
public class CommonVerifiers {

    @Inject
    private Logger log;

    @Inject
    private NetworkService networkService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private Base64Service base64Service;

    @Inject
    private DataMapperService dataMapperService;

    @Inject
    private Instance<AttestationFormatProcessor> supportedAttestationFormats;

    public void verifyRpIdHash(AuthData authData, String domain) {
        try {
            byte[] retrievedRpIdHash = authData.getRpIdHash();
            byte[] calculatedRpIdHash = DigestUtils.getSha256Digest().digest(domain.getBytes("UTF-8"));
            log.debug("rpIDHash from Domain    HEX {}", Hex.encodeHexString(calculatedRpIdHash));
            log.debug("rpIDHash from Assertion HEX {}", Hex.encodeHexString(retrievedRpIdHash));
            if (!Arrays.equals(retrievedRpIdHash, calculatedRpIdHash)) {
                log.warn("hash from domain doesn't match hash from assertion HEX");
                throw new Fido2RPRuntimeException("Hashes don't match");
            }
        } catch (UnsupportedEncodingException e) {
            throw new Fido2RPRuntimeException("This encoding is not supported");
        }
    }

	public String verifyRpDomain(JsonNode params) {
		// TODO: Use domains list to check specified documentDomain
        String documentDomain;
        if (params.hasNonNull("documentDomain")) {
            documentDomain = params.get("documentDomain").asText();
        } else {
            documentDomain = appConfiguration.getIssuer();
        }
        documentDomain = networkService.getHost(documentDomain);

        return documentDomain;
	}

    public void verifyCounter(int oldCounter, int newCounter) {
        log.debug("old counter {} new counter {} ", oldCounter, newCounter);
        if (newCounter == 0 && oldCounter == 0)
            return;
        if (newCounter <= oldCounter) {
            throw new Fido2RPRuntimeException("Counter did not increase");
        }
    }

    public void verifyCounter(int counter) {
        if (counter < 0) {
            throw new Fido2RPRuntimeException("Invalid field : counter");
        }
    }

    public void verifyAttestationOptions(JsonNode params) {
		long count = Arrays.asList(params.hasNonNull("username"),
				params.hasNonNull("displayName"),
				params.hasNonNull("attestation"))
				.parallelStream().filter(f -> f == false).count();
		if (count != 0) {
			throw new Fido2RPRuntimeException("Invalid parameters");
		}
    }

    public void verifyAssertionOptions(JsonNode params) {
		long count = Arrays.asList(params.hasNonNull("username"))
				.parallelStream().filter(f -> f == false).count();
		if (count != 0) {
			throw new Fido2RPRuntimeException("Invalid parameters");
		}
    }

    public void verifyBasicPayload(JsonNode params) {
        long count = Arrays.asList(params.hasNonNull("response"),
        		params.hasNonNull("type"),
        		params.hasNonNull("id")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid parameters");
        }
    }

    public String verifyBase64UrlString(JsonNode node, String fieldName) {
        String value = verifyThatFieldString(node, fieldName);
        try {
            base64Service.urlDecode(value);
        } catch (IllegalArgumentException e) {
            throw new Fido2RPRuntimeException("Invalid \"" + fieldName + "\"");
        }

        return value;
    }

    public String verifyBase64String(JsonNode node) {
        if ((node == null) || node.isNull()) {
            throw new Fido2RPRuntimeException("Invalid data");
        }
        String value = verifyThatBinary(node);
        if (value.isEmpty()) {
            throw new Fido2RPRuntimeException("Invalid data");
        }
        try {
            base64Service.decode(value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new Fido2RPRuntimeException("Invalid data");
        } catch (IllegalArgumentException e) {
            throw new Fido2RPRuntimeException("Invalid data");
        }

        return value;
    }

    protected String verifyThatString(JsonNode node, String fieldName) {
        if (!node.isTextual()) {
            if (node.fieldNames().hasNext()) {
                throw new Fido2RPRuntimeException("Invalid field " + node.fieldNames().next() + ". There is no filed " + fieldName);
            } else {
                throw new Fido2RPRuntimeException("Field hasn't sub field " + fieldName);
            }
        }

        return node.asText();
    }

    public String verifyThatFieldString(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if ((fieldNode == null || fieldNode.isNull())) {
            throw new Fido2RPRuntimeException("Invalid \"" + fieldName + "\"");
        }

        return verifyThatString(fieldNode, fieldName);
    }

    public String verifyThatNonEmptyString(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if ((fieldNode == null || fieldNode.isNull())) {
            throw new Fido2RPRuntimeException("Invalid \"" + fieldName + "\"");
        }

        String value = verifyThatString(fieldNode, fieldName);
        if (StringUtils.isEmpty(value)) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        } else {
            return value;
        }
    }

    public String verifyThatBinary(JsonNode node) {
        if (!node.isBinary()) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        }
        return node.asText();
    }

    public String verifyAuthData(JsonNode node) {
        if ((node == null) || node.isNull()) {
            throw new Fido2RPRuntimeException("Empty auth data");
        }

        String data = verifyThatBinary(node);
        if (data.isEmpty()) {
            throw new Fido2RPRuntimeException("Invalid field " + node);
        }
        return data;
    }

    public JsonNode verifyAuthStatement(JsonNode node) {
        if ((node == null) || node.isNull()) {
            throw new Fido2RPRuntimeException("Empty auth statement");
        }
        return node;
    }

    public int verifyAlgorithm(JsonNode alg, int registeredAlgorithmType) {
        if ((alg == null) || alg.isNull()) {
            throw new Fido2RPRuntimeException("Wrong algorithm");
        }
        int algorithmType = Integer.parseInt(alg.asText());
        if (algorithmType != registeredAlgorithmType) {
            throw new Fido2RPRuntimeException("Wrong algorithm");
        }
        return algorithmType;
    }

    public String verifyFmt(JsonNode fmtNode, String fieldName) {
        String fmt = verifyThatFieldString(fmtNode, fieldName);
        supportedAttestationFormats.stream().filter(f -> f.getAttestationFormat().getFmt().equals(fmt)).findAny()
                .orElseThrow(() -> new Fido2RPRuntimeException("Unsupported attestation format " + fmt));
        return fmt;
    }

    public void verifyAAGUIDZeroed(AuthData authData) {
        byte[] buf = authData.getAaguid();
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] != 0) {
                throw new Fido2RPRuntimeException("Invalid AAGUID");
            }
        }
    }

    public void verifyClientJSONTypeIsGet(JsonNode clientJsonNode) {
        verifyClientJSONType(clientJsonNode, "webauthn.get");
    }

    void verifyClientJSONType(JsonNode clientJsonNode, String type) {
        if (clientJsonNode.has("type")) {
            if (!type.equals(clientJsonNode.get("type").asText())) {
                throw new Fido2RPRuntimeException("Invalid client json parameters");
            }
        }
    }

    public void verifyClientJSONTypeIsCreate(JsonNode clientJsonNode) {
        verifyClientJSONType(clientJsonNode, "webauthn.create");
    }

    public JsonNode verifyClientJSON(JsonNode responseNode) {
        JsonNode clientJsonNode = null;
        try {
            if (!responseNode.hasNonNull("clientDataJSON")) {
                throw new Fido2RPRuntimeException("Client data JSON is missing");
            }
            clientJsonNode = dataMapperService
                    .readTree(new String(base64Service.urlDecode(responseNode.get("clientDataJSON").asText()), Charset.forName("UTF-8")));
            if (clientJsonNode == null) {
                throw new Fido2RPRuntimeException("Client data JSON is empty");
            }
        } catch (IOException e) {
            throw new Fido2RPRuntimeException("Can't parse message");
        }

        long count = Arrays.asList(clientJsonNode.hasNonNull("challenge"), clientJsonNode.hasNonNull("origin"), clientJsonNode.hasNonNull("type")
        ).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid client json parameters");
        }
        verifyBase64UrlString(clientJsonNode, "challenge");

        if (clientJsonNode.hasNonNull("tokenBinding")) {
        	JsonNode tokenBindingNode = clientJsonNode.get("tokenBinding");
        	
        	if (tokenBindingNode.hasNonNull("status")) {
        		String status = verifyThatFieldString(tokenBindingNode, "status");
        		verifyTokenBindingSupport(status);
        	} else {
                throw new Fido2RPRuntimeException("Invalid tokenBinding entry. it should contaiss status");
        	}
            if (tokenBindingNode.hasNonNull("id")) {
            	verifyThatFieldString(tokenBindingNode, "id");
            }
        }

        String origin = verifyThatFieldString(clientJsonNode, "origin");
        if (origin.isEmpty()) {
            throw new Fido2RPRuntimeException("Client data origin parameter should be string");
        }
        
        return clientJsonNode;
    }

    public void verifyTPMVersion(JsonNode ver) {
        if (!"2.0".equals(ver.asText())) {
            throw new Fido2RPRuntimeException("Invalid TPM Attestation version");
        }
    }

    public AttestationConveyancePreference verifyAttestationConveyanceType(JsonNode params) {
    	AttestationConveyancePreference attestationConveyancePreference = null;
        if (params.has("attestation")) {
            String type = verifyThatFieldString(params, "attestation");
            attestationConveyancePreference = AttestationConveyancePreference.valueOf(type);
        }

        if (attestationConveyancePreference == null) {
        	attestationConveyancePreference = AttestationConveyancePreference.direct;
        }
        
        return attestationConveyancePreference;
    }

    public TokenBindingSupport verifyTokenBindingSupport(String status) {
    	if (status == null) {
    		return null;
    	}

        try {
        	TokenBindingSupport tokenBindingSupportEnum = TokenBindingSupport.fromStatusValue(status);
        	if (tokenBindingSupportEnum == null) {
                throw new Fido2RPRuntimeException("Wrong token binding status parameter " + status);
        	} else {
        		return tokenBindingSupportEnum;
        	}
        } catch (Exception e) {
            throw new Fido2RPRuntimeException("Wrong token binding status parameter " + e.getMessage(), e);
        }
    }

    public AuthenticatorAttachment verifyAuthenticatorAttachment(JsonNode authenticatorAttachment) {
    	if (authenticatorAttachment == null) {
    		return null;
    	}

        try {
        	AuthenticatorAttachment authenticatorAttachmentEnum = AuthenticatorAttachment.fromAttachmentValue(authenticatorAttachment.asText());
        	if (authenticatorAttachmentEnum == null) {
                throw new Fido2RPRuntimeException("Wrong authenticator attachment parameter " + authenticatorAttachment);
        	} else {
        		return authenticatorAttachmentEnum;
        	}
        } catch (Exception e) {
            throw new Fido2RPRuntimeException("Wrong authenticator attachment parameter " + e.getMessage(), e);
        }
    }

    public UserVerification verifyUserVerification(JsonNode userVerification) {
    	if (userVerification == null) {
    		return null;
    	}

    	try {
    		UserVerification userVerificationEnum = UserVerification.valueOf(userVerification.asText());
        	if (userVerificationEnum == null) {
                throw new Fido2RPRuntimeException("Wrong user verification parameter " + userVerification);
        	} else {
        		return userVerificationEnum;
        	}
        } catch (Exception e) {
            throw new Fido2RPRuntimeException("Wrong user verification parameter " + e.getMessage(), e);
        }
    }

    public UserVerification prepareUserVerification(JsonNode params) {
        UserVerification userVerification = UserVerification.preferred;

        if (params.hasNonNull("userVerification")) {
        	userVerification = verifyUserVerification(params.get("userVerification"));
        }

		return userVerification;
	}

    public Boolean verifyRequireResidentKey(JsonNode requireResidentKey) {
    	if (requireResidentKey == null) {
    		return null;
    	}

        try {
            return requireResidentKey.asBoolean();
        } catch (Exception e) {
            throw new Fido2RPRuntimeException("Wrong authenticator attachment parameter " + e.getMessage(), e);
        }
    }

    public String verifyAssertionType(JsonNode typeNode, String fieldName) {
        String type = verifyThatFieldString(typeNode, fieldName);
        if (!"public-key".equals(type)) {
            throw new Fido2RPRuntimeException("Invalid type");
        }
        return type;
    }

	public String verifyCredentialId(CredAndCounterData attestationData, JsonNode params) {
        String paramsKeyId = verifyBase64UrlString(params, "id");
        
        if (StringHelper.isEmpty(paramsKeyId)) {
            throw new Fido2RPRuntimeException("Credential id attestationObject and response id mismatch");
        }
        
//		String attestationDataCredId = attestationData.getCredId();
//        if (!StringHelper.compare(attestationDataCredId, paramsKeyId)) {
//            throw new Fido2RPRuntimeException("Credential id attestationObject and response id mismatch");
//        }
        
        return paramsKeyId;
	}

	public String getChallenge(JsonNode clientDataJSONNode) {
		try {
			String clientDataChallenge = base64Service
					.urlEncodeToStringWithoutPadding(base64Service.urlDecode(clientDataJSONNode.get("challenge").asText()));

			return clientDataChallenge;
		} catch (Exception ex) {
			throw new Fido2RPRuntimeException("Can't get challenge from clientData");
		}
	}

	public int verifyTimeout(JsonNode params) {
        int timeout = 90;
        if (params.hasNonNull("timeout")) {
        	timeout = params.get("timeout").asInt(timeout);
        }

        return timeout;
	}

    public void verifyThatMetadataIsValid(JsonNode metadata) {
        long count = Arrays.asList(metadata.hasNonNull("aaguid"), metadata.hasNonNull("assertionScheme"), metadata.hasNonNull("attestationTypes"),
                metadata.hasNonNull("description")).parallelStream().filter(f -> f == false).count();
        if (count != 0) {
            throw new Fido2RPRuntimeException("Invalid parameters in metadata");
        }
    }

}
