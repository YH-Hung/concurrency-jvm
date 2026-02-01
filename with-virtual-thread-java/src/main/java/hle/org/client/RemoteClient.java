package hle.org.client;

import hle.org.pool.PooledResource;

/**
 * Interface representing a generic remote client that can be pooled.
 * This is a technology-agnostic interface that can be implemented by:
 * - CORBA clients
 * - RMI clients
 * - gRPC clients
 * - HTTP clients
 * - Any blocking I/O client
 * 
 * @param <REQ> the request type
 * @param <RES> the response type
 */
public interface RemoteClient<REQ, RES> extends PooledResource<RES> {

    /**
     * Executes a request against the remote service.
     * This method may block while waiting for the response.
     * 
     * @param request the request to send
     * @return the response from the remote service
     * @throws RemoteClientException if the request fails
     */
    RES execute(REQ request) throws RemoteClientException;

    /**
     * Gets the endpoint this client is connected to.
     * 
     * @return the endpoint URL or description
     */
    String getEndpoint();

    /**
     * Returns true if this client is currently connected.
     */
    boolean isConnected();
}
