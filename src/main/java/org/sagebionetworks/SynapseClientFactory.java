package org.sagebionetworks;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.SynapseProfileProxy;
import org.sagebionetworks.client.exceptions.SynapseBadRequestException;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseDeprecatedServiceException;
import org.sagebionetworks.client.exceptions.SynapseForbiddenException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.client.exceptions.SynapseServerException;
import org.sagebionetworks.client.exceptions.SynapseTermsOfUseException;
import org.sagebionetworks.client.exceptions.SynapseUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SynapseClientFactory {

	private static Logger log = LoggerFactory.getLogger(SynapseClientFactory.class);

	private static final List<Class<? extends SynapseServerException>> NO_RETRY_EXCEPTIONS = Arrays.asList(
			SynapseResultNotReadyException.class,
			SynapseNotFoundException.class,
			SynapseBadRequestException.class,
			SynapseConflictingUpdateException.class,
			SynapseDeprecatedServiceException.class,
			SynapseForbiddenException.class, 
			SynapseTermsOfUseException.class,
			SynapseUnauthorizedException.class,
			SynapseConflictingUpdateException.class
			); // TODO need to catch and not retry 409 too
	
	private static final Integer[] NO_RETRY_STATUSES = new Integer[] {409};

	private static SynapseClient createSynapseClientIntern() {
		SynapseClientImpl scIntern = new SynapseClientImpl();
		scIntern.setAuthEndpoint("https://repo-prod.prod.sagebase.org/auth/v1");
		scIntern.setRepositoryEndpoint("https://repo-prod.prod.sagebase.org/repo/v1");
		scIntern.setFileEndpoint("https://repo-prod.prod.sagebase.org/file/v1");

		return SynapseProfileProxy.createProfileProxy(scIntern);
	}

	public static SynapseClient createSynapseClient() {
		final SynapseClient synapseClientIntern = createSynapseClientIntern();

		final ExponentialBackoffRunner exponentialBackoffRunner = new ExponentialBackoffRunner(
				NO_RETRY_EXCEPTIONS, NO_RETRY_STATUSES, ExponentialBackoffRunner.DEFAULT_NUM_RETRY_ATTEMPTS);

		InvocationHandler handler = new InvocationHandler() {
			public Object invoke(final Object proxy, final Method method, final Object[] args)
					throws Throwable {
				return exponentialBackoffRunner.execute(new Executable<Object>() {
					public Object execute() throws Throwable {
						try {
							Object result = method.invoke(synapseClientIntern, args);
							return result;
						} catch (IllegalAccessException  e) {
							throw new RuntimeException(e);
						} catch (InvocationTargetException e) {
							if (e.getCause()==null) throw e; else throw e.getCause();
						}
					}
				});
			}
		};

		return (SynapseClient) Proxy.newProxyInstance(SynapseClient.class.getClassLoader(),
				new Class[] { SynapseClient.class },
				handler);
	}

}
