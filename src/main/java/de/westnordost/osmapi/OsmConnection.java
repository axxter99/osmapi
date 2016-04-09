package de.westnordost.osmapi;


import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import de.westnordost.osmapi.errors.OsmApiReadResponseException;
import de.westnordost.osmapi.errors.OsmAuthenticationException;
import de.westnordost.osmapi.errors.OsmConnectionException;
import de.westnordost.osmapi.errors.RedirectedException;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.basic.DefaultOAuthConsumer;
import oauth.signpost.exception.OAuthException;
import oauth.signpost.exception.OAuthExpectationFailedException;

/** Talks with the <a href="http://wiki.openstreetmap.org/wiki/API_v0.6">OpenStreetMap API 0.6</a>,
 * acts as a basis for data access objects for openstreetmap data accessible through the API.
 *
 * Requests made through this object can generally throw three two kinds of unchecked exceptions:
 * <ul>
 *     <li>OsmConnectionException:
 *                                 if an error occurs while communicating with the server that is
 *                                 independent of the actual request made. Usually a wrapped
 *                                 IOException and similar, so nothing the application can recover
 *                                 from.</li>
 *
 *     <li>OsmApiReadResponseException:
 *                                 an error while parsing the server's response; any exception
 *                                 thrown by a response handler will be wrapped in this exception.
 *                                 It is up to the handler to decide which Exceptions it throws,
 *                                 but if it is thrown during parsing, this hints to a programming
 *                                 error.</li>
 *     <li>OsmApiException:
 *                                 if there is something wrong with the user's request, i.e. the
 *                                 request itself was invalid - so, likely a programming error.</li>
 *
 * So, if we used checked exceptions in this library, then OsmConnectionException would be the
 * checked one that should be accounted for.
 * </ul>
 *
 *
 * A OsmConnection is reusable and thread safe (because it is immutable).
 */
public class OsmConnection
{
	/** charset we use for everything */
	public static final String CHARSET = "UTF-8";

	private static final int DEFAULT_TIMEOUT = 45 * 1000;

	private final int timeout;
	private final String apiUrl;
	private final String userAgent;
	private final OAuthConsumer oauth;

	/**
	 * Create a new OsmConnection with the given preferences
	 * @param apiUrl the URL to the API
	 * @param userAgent the user agent this application should identify as
	 * @param oauth oauth consumer to use to authenticate this app. If this is null, any attempt to
	 *              make an API call that requires authentication will throw an OsmAuthenticationException
	 * @param timeout for the server connection
	 */
	public OsmConnection(String apiUrl, String userAgent, OAuthConsumer oauth, Integer timeout)
	{
		this.apiUrl = apiUrl;
		this.userAgent = userAgent;
		this.oauth = oauth;
		this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
	}

	/**
	 * Create a new OsmConnection with the given preferences
	 * @param apiUrl the URL to the API
	 * @param userAgent the user agent this application should identify as
	 * @param oauth oauth consumer to use to authenticate this app. If this is null, any attempt to
	 *              make an API call that requires authentication will throw an
	 *              OsmAuthenticationException
	 */
	public OsmConnection(String apiUrl, String userAgent, OAuthConsumer oauth)
	{
		this(apiUrl, userAgent, oauth, null);
	}

	public String getUserAgent()
	{
		return userAgent;
	}

	public String getApiUrl()
	{
		return apiUrl;
	}

	public OAuthConsumer getOAuth()
	{
		return oauth;
	}

	public String getCharset()
	{
		return CHARSET;
	}

	public <T> T makeRequest(String call, ApiResponseReader<T> reader)
	{
		return makeRequest(call, null, false, null, reader);
	}

	public <T> T  makeAuthenticatedRequest(String call, String method, ApiResponseReader<T> reader)
	{
		return makeRequest(call, method, true, null, reader);
	}

	public <T> T makeAuthenticatedRequest(String call, String method, ApiRequestWriter writer,
										 ApiResponseReader<T> reader)
	{
		return makeRequest(call, method, true, writer, reader);
	}

	public void makeAuthenticatedRequest(String call, String method)
	{
		makeRequest(call, method, true, null, null);
	}

	public void makeAuthenticatedRequest(String call, String method, ApiRequestWriter writer)
	{
		makeRequest(call, method, true, writer, null);
	}

	public <T> T makeRequest(String call, String method, boolean authenticate,
							  ApiRequestWriter writer, ApiResponseReader<T> reader)
	{
		HttpURLConnection connection = null;
		try
		{
			connection = sendRequest(call, method, authenticate, writer);
			handleResponseCode(connection);

			if(reader != null) return handleResponse(connection, reader);
			else return null;
		}
		catch(IOException e)
		{
			throw new OsmConnectionException(e);
		}
		catch(OAuthException e)
		{
			// because it was the user's fault that he did not supply an oauth consumer and the
			// error is kinda related with the call he made
			throw new OsmAuthenticationException(e);
		}
		finally
		{
			if(connection != null) connection.disconnect();
		}
	}

	private HttpURLConnection sendRequest(
			String call, String method, boolean authenticate, ApiRequestWriter writer)
			throws IOException, OAuthException
	{
		HttpURLConnection connection = openConnection(call);
		if(method != null)
		{
			connection.setRequestMethod(method);
		}

		if(writer != null && writer.getContentType() != null)
		{
			connection.setRequestProperty("Content-Type", writer.getContentType());
			connection.setRequestProperty("charset", CHARSET.toLowerCase(Locale.UK));
		}

		if(authenticate)
		{
			createOAuthConsumer().sign(connection);
		}

		if(writer != null)
		{
			sendRequestPayload(connection, writer);
		}

		return connection;
	}

	private void sendRequestPayload(HttpURLConnection connection, ApiRequestWriter writer)
			throws IOException
	{
		connection.setDoOutput(true);

		OutputStream out = null;
		try
		{
			out = connection.getOutputStream();
			writer.write(out);
		}
		catch(IOException e)
		{
			connection.disconnect();
			throw e;
		}
		finally
		{
			if (out != null)
			{
				out.close();
			}
		}
	}

	private HttpURLConnection openConnection(String call) throws IOException
	{
		URL url = new URL(new URL(apiUrl), call);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		// hotel wifi with signon
		if (!url.getHost().equals(connection.getURL().getHost()))
		{
			throw new RedirectedException();
		}

		if(userAgent != null)
		{
			connection.setRequestProperty("User-Agent", userAgent);
		}
		connection.setConnectTimeout(timeout);
		connection.setReadTimeout(timeout);

		// default is method=GET, doInput=true, doOutput=false

		return connection;
	}

	private OAuthConsumer createOAuthConsumer() throws OAuthExpectationFailedException
	{
		if(oauth == null)
		{
			throw new OAuthExpectationFailedException(
					"This class has been initialized without a OAuthConsumer. Only API calls that" +
					" do not require authentication can be made.");
		}

		// "clone" the original consumer every time because the consumer is documented to be not
		// thread safe and maybe multiple threads are making calls to this class
		OAuthConsumer consumer = new DefaultOAuthConsumer(oauth.getConsumerKey(), oauth.getConsumerSecret());
		consumer.setTokenWithSecret(oauth.getToken(), oauth.getTokenSecret());
		return consumer;
	}

	private <T> T handleResponse(HttpURLConnection connection, ApiResponseReader<T> reader)
			throws IOException
	{
		InputStream in = null;
		try
		{
			in = new BufferedInputStream(connection.getInputStream());
			return reader.parse(in);
		}
		catch (Exception e)
		{
			throw new OsmApiReadResponseException(e);
		}
		finally
		{
			if (in != null)
			{
				in.close();
			}
		}
	}

	private void handleResponseCode(HttpURLConnection connection) throws IOException
	{
		int httpResponseCode = connection.getResponseCode();
		// actually any response code between 200 and 299 is a "success" but may need additional
		// handling. Since the Osm Api only returns 200 on success curently, this check is fine
		if(httpResponseCode != HttpURLConnection.HTTP_OK)
		{
			String responseMessage = connection.getResponseMessage();
			throw OsmApiErrorFactory.createError(httpResponseCode, responseMessage);
		}
	}
}