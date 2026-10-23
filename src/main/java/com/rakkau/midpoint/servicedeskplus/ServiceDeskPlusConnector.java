/*
 * Copyright 2026 RAKKAU
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rakkau.midpoint.servicedeskplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.PoolableConnector;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * midPoint / ConnId connector for ManageEngine ServiceDesk Plus (REST API v3, {@code /api/v3/users}).
 *
 * <p>Identifiers: {@code __UID__} is the SDP user {@code id} (immutable and unique). {@code __NAME__} is the SDP
 * {@code name} (display name), the only field that ServiceDesk Plus requires for every user; e-mail and the other
 * fields are optional in SDP and therefore optional here.</p>
 */
@ConnectorClass(displayNameKey = "serviceDesk.connector.display", configurationClass = ServiceDeskConfiguration.class)
public class ServiceDeskPlusConnector
        implements PoolableConnector, TestOp, SchemaOp, SearchOp<Filter>, CreateOp, UpdateOp, DeleteOp {

    private static final Log LOG = Log.getLog(ServiceDeskPlusConnector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ATTR_EMAIL = "email";
    static final String ATTR_FIRST_NAME = "firstName";
    static final String ATTR_LAST_NAME = "lastName";
    static final String ATTR_PHONE = "phone";
    static final String ATTR_MOBILE = "mobile";
    static final String ATTR_DEPARTMENT = "department";
    static final String ATTR_EMPLOYEE_ID = "employeeId";
    static final String ATTR_IS_TECHNICIAN = "isTechnician";

    private static final String FIELD_EMAIL = "email_id";
    private static final String FIELD_NAME = "name";

    /** Plain string attributes that can be written: ConnId attribute -> SDP JSON field. */
    private static final Map<String, String> WRITABLE_STRING_FIELDS = new LinkedHashMap<>();
    static {
        WRITABLE_STRING_FIELDS.put(ATTR_EMAIL, FIELD_EMAIL);
        WRITABLE_STRING_FIELDS.put(ATTR_FIRST_NAME, "first_name");
        WRITABLE_STRING_FIELDS.put(ATTR_LAST_NAME, "last_name");
        WRITABLE_STRING_FIELDS.put(ATTR_PHONE, "phone");
        WRITABLE_STRING_FIELDS.put(ATTR_MOBILE, "mobile");
        WRITABLE_STRING_FIELDS.put(ATTR_EMPLOYEE_ID, "employee_id");
    }

    private ServiceDeskConfiguration configuration;
    private SdpHttpClient client;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void init(Configuration configuration) {
        this.configuration = (ServiceDeskConfiguration) configuration;
        this.configuration.validate();
        this.client = new SdpHttpClient(this.configuration);
        LOG.ok("ServiceDesk Plus connector initialised");
    }

    @Override
    public void dispose() {
        this.client = null;
        this.configuration = null;
    }

    @Override
    public void checkAlive() {
        test();
    }

    @Override
    public ServiceDeskConfiguration getConfiguration() {
        return configuration;
    }

    // ------------------------------------------------------------------ test / schema

    @Override
    public void test() {
        // Authentication, permission, TLS and connectivity problems surface as specific ConnId exceptions.
        client.listUsers(1, 1, null, null);
    }

    @Override
    public Schema schema() {
        ObjectClassInfoBuilder account = new ObjectClassInfoBuilder();
        account.setType(ObjectClass.ACCOUNT_NAME);
        // __NAME__ (SDP name, mandatory) and __UID__ (SDP id) are added by ConnId automatically.
        account.addAttributeInfo(attribute(ATTR_EMAIL, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_FIRST_NAME, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_LAST_NAME, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_PHONE, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_MOBILE, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_DEPARTMENT, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_EMPLOYEE_ID, String.class, true, false));
        account.addAttributeInfo(attribute(ATTR_IS_TECHNICIAN, Boolean.class, false, false)); // read-only

        SchemaBuilder schemaBuilder = new SchemaBuilder(ServiceDeskPlusConnector.class);
        schemaBuilder.defineObjectClass(account.build());
        return schemaBuilder.build();
    }

    private static AttributeInfo attribute(String name, Class<?> type, boolean writable, boolean required) {
        return new AttributeInfoBuilder(name, type)
                .setRequired(required)
                .setMultiValued(false)
                .setCreateable(writable)
                .setUpdateable(writable)
                .setReadable(true)
                .setReturnedByDefault(true)
                .build();
    }

    // ------------------------------------------------------------------ create / update / delete

    @Override
    public Uid create(ObjectClass objectClass, Set<Attribute> createAttributes, OperationOptions options) {
        requireAccount(objectClass);
        ObjectNode user = buildUserNode(createAttributes, false);
        if (!user.hasNonNull(FIELD_NAME)) {
            throw new InvalidAttributeValueException("Attribute __NAME__ (display name) is required to create a user");
        }
        JsonNode root = client.createUser(user);
        String id = root.path("user").path("id").asText("");
        if (id.isBlank()) {
            throw new ConnectorException("ServiceDesk Plus created the user but did not return its id");
        }
        LOG.info("Created ServiceDesk Plus user with id {0}", id);
        return new Uid(id);
    }

    @Override
    public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> replaceAttributes, OperationOptions options) {
        requireAccount(objectClass);
        ObjectNode user = buildUserNode(replaceAttributes, true);
        if (user.isEmpty()) {
            LOG.ok("Update of user {0} contained no writable attributes; nothing to do", uid.getUidValue());
            return uid;
        }
        client.updateUser(uid.getUidValue(), user);
        LOG.info("Updated ServiceDesk Plus user with id {0}", uid.getUidValue());
        return uid;
    }

    @Override
    public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
        requireAccount(objectClass);
        client.deleteUser(uid.getUidValue());
        LOG.info("Deleted ServiceDesk Plus user with id {0}", uid.getUidValue());
    }

    private ObjectNode buildUserNode(Set<Attribute> attributes, boolean update) {
        ObjectNode node = MAPPER.createObjectNode();
        for (Attribute attribute : attributes) {
            String name = attribute.getName();
            if (Uid.NAME.equals(name)) {
                continue;
            }
            String value = singleValue(attribute);
            boolean present = value != null && !value.isBlank();
            if (Name.NAME.equals(name)) {
                if (present) {
                    node.put(FIELD_NAME, value); // mandatory in SDP: never cleared
                }
            } else if (WRITABLE_STRING_FIELDS.containsKey(name)) {
                String field = WRITABLE_STRING_FIELDS.get(name);
                if (present) {
                    node.put(field, value);
                } else if (update) {
                    node.putNull(field); // replace with "no value" clears the field
                }
            } else if (ATTR_DEPARTMENT.equals(name)) {
                if (present) {
                    node.putObject("department").put("name", value);
                } else if (update) {
                    node.putNull("department");
                }
            } else {
                throw new InvalidAttributeValueException("Unsupported attribute: " + name);
            }
        }
        return node;
    }

    private static String singleValue(Attribute attribute) {
        List<Object> values = attribute.getValue();
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return null;
        }
        if (values.size() > 1) {
            throw new InvalidAttributeValueException("Attribute " + attribute.getName() + " is single-valued");
        }
        return values.get(0).toString();
    }

    // ------------------------------------------------------------------ search

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        // The ConnId filter is applied as received: pushed to SDP for UID / e-mail lookups, in memory otherwise.
        return new FilterTranslator<Filter>() {
            @Override
            public List<Filter> translate(Filter filter) {
                return Collections.singletonList(filter);
            }
        };
    }

    @Override
    public void executeQuery(ObjectClass objectClass, Filter query, ResultsHandler handler, OperationOptions options) {
        requireAccount(objectClass);

        String uid = equalsValue(query, Uid.NAME);
        if (uid != null) {
            fetchSingle(uid, query, handler);
            return;
        }
        // Lookup by e-mail is evaluated by ServiceDesk Plus through search_fields.
        String email = equalsValue(query, ATTR_EMAIL);
        String searchField = email != null ? FIELD_EMAIL : null;

        final int pageSize = configuration.getPageSize();
        int startIndex = 1;
        boolean hasMoreRows;
        do {
            JsonNode root = client.listUsers(startIndex, pageSize, searchField, email);
            JsonNode users = root.path("users");
            if (!users.isArray()) {
                throw new ConnectorException("Unexpected ServiceDesk Plus response: missing array 'users'");
            }
            for (JsonNode user : users) {
                ConnectorObject object = toConnectorObject(user);
                if (object == null || (query != null && !query.accept(object))) {
                    continue;
                }
                if (!handler.handle(object)) {
                    return;
                }
            }
            hasMoreRows = root.path("list_info").path("has_more_rows").asBoolean(false) && !users.isEmpty();
            startIndex += pageSize;
        } while (hasMoreRows);
    }

    private void fetchSingle(String uid, Filter query, ResultsHandler handler) {
        try {
            JsonNode root = client.getUser(uid);
            ConnectorObject object = toConnectorObject(root.path("user"));
            if (object != null && query.accept(object)) {
                handler.handle(object);
            }
        } catch (UnknownUidException e) {
            LOG.ok("User {0} not found; returning an empty result", uid); // ConnId semantics: empty result, not an error
        }
    }

    private static String equalsValue(Filter filter, String attributeName) {
        if (filter instanceof EqualsFilter equalsFilter
                && attributeName.equals(equalsFilter.getAttribute().getName())) {
            return AttributeUtil.getStringValue(equalsFilter.getAttribute());
        }
        return null;
    }

    private ConnectorObject toConnectorObject(JsonNode user) {
        String id = text(user, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        // "name" is mandatory in SDP; the fallbacks only protect against unexpected data.
        String name = text(user, FIELD_NAME);
        if (name == null || name.isBlank()) name = text(user, FIELD_EMAIL);
        if (name == null || name.isBlank()) name = id;

        ConnectorObjectBuilder builder = new ConnectorObjectBuilder()
                .setObjectClass(ObjectClass.ACCOUNT)
                .setUid(new Uid(id))
                .setName(new Name(name));

        addIfPresent(builder, ATTR_EMAIL, text(user, FIELD_EMAIL));
        addIfPresent(builder, ATTR_FIRST_NAME, text(user, "first_name"));
        addIfPresent(builder, ATTR_LAST_NAME, text(user, "last_name"));
        addIfPresent(builder, ATTR_PHONE, text(user, "phone"));
        addIfPresent(builder, ATTR_MOBILE, text(user, "mobile"));
        addIfPresent(builder, ATTR_EMPLOYEE_ID, text(user, "employee_id"));
        if (user.path("is_technician").isBoolean()) {
            builder.addAttribute(AttributeBuilder.build(ATTR_IS_TECHNICIAN, user.path("is_technician").asBoolean()));
        }
        JsonNode department = user.path("department");
        addIfPresent(builder, ATTR_DEPARTMENT, department.isObject() ? text(department, "name") : text(user, "department"));
        return builder.build();
    }

    private static void addIfPresent(ConnectorObjectBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.addAttribute(AttributeBuilder.build(name, value));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        return value.asText();
    }

    private static void requireAccount(ObjectClass objectClass) {
        if (!ObjectClass.ACCOUNT.equals(objectClass)) {
            throw new ConnectorException("Unsupported object class: " + objectClass);
        }
    }
}
