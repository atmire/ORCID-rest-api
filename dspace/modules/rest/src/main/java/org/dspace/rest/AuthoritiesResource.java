/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 * <p>
 * http://www.dspace.org/license/
 */
package org.dspace.rest;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.authority.AuthorityUtil;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.AuthorityValueFinder;
import org.dspace.authority.PersonAuthorityValue;
import org.dspace.authority.factory.AuthorityServiceFactory;
import org.dspace.authority.indexer.AuthorityIndexingService;
import org.dspace.authority.orcid.Orcidv2AuthorityValue;
import org.dspace.authority.service.AuthorityValueService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.discovery.IndexingService;
import org.dspace.discovery.SearchServiceException;
import org.dspace.rest.exceptions.ContextException;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Iterator;

import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.*;
import static javax.ws.rs.core.Response.status;

@SuppressWarnings("deprecation")
@Path("/authorities")
public class AuthoritiesResource extends Resource {

    private static final Logger log = Logger.getLogger(AuthoritiesResource.class);

    private static final AuthorityIndexingService authorityIndexingService = new DSpace().getSingletonService(AuthorityIndexingService.class);
    private static final IndexingService indexingService = new DSpace().getSingletonService(IndexingService.class);
    private static final AuthorityValueService authorityValueService = AuthorityServiceFactory.getInstance().getAuthorityValueService();

    private static final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    private static final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private static final ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    @PUT
    @Path("/{authority_id}/value")
    @Consumes(TEXT_PLAIN)
    public Response updateAuthorityValue(@PathParam("authority_id") String authorityId, String value,
                                         @QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent,
                                         @QueryParam("xforwardedfor") String xforwardedfor, @Context HttpHeaders headers, @Context HttpServletRequest request)
            throws WebApplicationException {

        if (!configurationService.getBooleanProperty("authority.allow-rest-updates.person", false)) {
            throw new WebApplicationException(BAD_REQUEST);
        }

        log.info("Updating value of authority (id: " + authorityId + ") to " + value + ".");

        org.dspace.core.Context context = null;
        try {
            context = createContext();

            if (!authorizeService.isAdmin(context)) {
                context.abort();
                throw new WebApplicationException(UNAUTHORIZED);
            }

            AuthorityValue authorityValue = new AuthorityValueFinder().findByUID(context, authorityId);

            if (!(authorityValue instanceof PersonAuthorityValue)) {
                context.abort();
                throw new IllegalArgumentException("Provided authority is not a person. Only person authorities can be updated.");
            }

            authorityValue.setValue(value);

            if (!(authorityValue instanceof Orcidv2AuthorityValue)) {
                authorityValue = authorityValueService.update(authorityValue);
            } else if (!configurationService.getBooleanProperty("authority.allow-rest-updates.orcid", false)) {
                throw new WebApplicationException(BAD_REQUEST);
            }

            authorityIndexingService.indexContent(authorityValue);
            new AuthorityUtil().deleteAuthorityValueById(authorityId);

            log.info("Deleted authority with id: " + authorityId + " and added authority with id: " + authorityValue.getId());

            authorityIndexingService.commit();

            Iterator<Item> itemIterator = itemService.findByMetadataFieldAuthority(context, authorityValue.getField().replaceAll("_", "."), authorityId);
            while (itemIterator.hasNext()) {
                Item item = context.reloadEntity(itemIterator.next());

                for (MetadataValue metadataValue : itemService.getMetadataByMetadataString(item, authorityValue.getField().replaceAll("_", "."))) {
                    if (authorityId.equals(metadataValue.getAuthority())) {
                        metadataValue.setAuthority(authorityValue.getId());
                        metadataValue.setValue(authorityValue.getValue());
                    }
                }

                indexingService.indexContent(context, item, true);
            }

            indexingService.commit();
            context.complete();
            log.info("Updated authority metadata values & discovery index.");

        } catch (IllegalArgumentException | SQLException | SearchServiceException | IOException | SolrServerException | ContextException | AuthorizeException e) {
            processException(
                    "Could not update value of authority (id: " + authorityId + "). Message: " + e.getMessage(), context
            );
        } finally {
            processFinally(context);
        }

        log.info("Value of authority (id: " + authorityId + ") was successfully updated.");

        return status(OK).build();
    }
}
