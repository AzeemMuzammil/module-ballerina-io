/*
 * Copyright (c) 2022 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.io.nativeimpl;

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.StructureType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.TypeUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.stdlib.io.utils.IOUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.stdlib.io.nativeimpl.RecordChannelUtils.getAllRecords;
import static io.ballerina.stdlib.io.nativeimpl.RecordChannelUtils.hasNext;
import static io.ballerina.stdlib.io.utils.IOConstants.CSV_ITERATOR;
import static io.ballerina.stdlib.io.utils.IOConstants.CSV_RETURN_TYPE;
import static io.ballerina.stdlib.io.utils.IOConstants.ITERATOR_NAME;
import static io.ballerina.stdlib.io.utils.IOConstants.READABLE_CHARACTER_CHANNEL;
import static io.ballerina.stdlib.io.utils.IOConstants.READABLE_TEXT_RECORD_CHANNEL;
import static io.ballerina.stdlib.io.utils.IOUtils.getIOPackage;

/**
 * This class hold Java external functions for csv reading APIs.
 *
 * * @since 1.3.0
 */
public final class CsvChannelUtils {

    private CsvChannelUtils() {}

    private static final BString FIELD_SEPERATOR = StringUtils.fromString(",");
    private static final BString ROW_SEPERATOR = StringUtils.fromString("");
    private static final BString FORMAT = StringUtils.fromString("CSV");
    private static final BString ENCODING = StringUtils.fromString("UTF-8");

    public static Object fileReadCsv(BString path, int skipHeaders, BTypedesc typeDesc) {
        Object byteChannelObject = ByteChannelUtils.openReadableFile(path);
        if (byteChannelObject instanceof BError) {
            return byteChannelObject;
        }
        BObject byteChannel = (BObject) byteChannelObject;
        BObject characterChannel = ValueCreator.createObjectValue(getIOPackage(),
            READABLE_CHARACTER_CHANNEL, byteChannel, ENCODING);
        BObject textRecordChannel = ValueCreator.createObjectValue(getIOPackage(),
            READABLE_TEXT_RECORD_CHANNEL, characterChannel, FIELD_SEPERATOR, ROW_SEPERATOR, FORMAT);
        textRecordChannel.addNativeData(CSV_RETURN_TYPE, typeDesc);
        while (hasNext(textRecordChannel)) {
            return getAllRecords(textRecordChannel, skipHeaders, typeDesc);
        }
        return null;
    }

    public static Object createCsvAsStream(BString path, BTypedesc typeDesc) {
        Type describingType = TypeUtils.getReferredType(typeDesc.getDescribingType());
        Object byteChannelObject = ByteChannelUtils.openReadableFile(path);
        if (byteChannelObject instanceof BError) {
            return byteChannelObject;
        }
        BObject byteChannel = (BObject) byteChannelObject;
        BObject characterChannel = ValueCreator.createObjectValue(getIOPackage(),
            READABLE_CHARACTER_CHANNEL, byteChannel, ENCODING);
        BObject textRecordChannel = ValueCreator.createObjectValue(getIOPackage(),
            READABLE_TEXT_RECORD_CHANNEL, characterChannel, FIELD_SEPERATOR, ROW_SEPERATOR, FORMAT);
        BObject recordIterator = ValueCreator.createObjectValue(getIOPackage(), CSV_ITERATOR);
        recordIterator.addNativeData(CSV_RETURN_TYPE, typeDesc);
        recordIterator.addNativeData(ITERATOR_NAME, textRecordChannel);
        return ValueCreator.createStreamValue(
                TypeCreator.createStreamType(describingType), recordIterator);
    }

    public static Object getStruct(String[] fields, final StructureType structType, ArrayList<String> headerNames) {
        Map<String, Field> internalStructFields = structType.getFields();
        int fieldLength = headerNames.size();
        Map<String, Object> struct = null;
        if (fields.length > 0) {
            struct = new HashMap<>();
            for (int i = 0; i < fieldLength; i++) {
                final Field internalStructField = internalStructFields.get(headerNames.get(i));
                int type = TypeUtils.getReferredType(internalStructField.getFieldType()).getTag();
                String fieldName = internalStructField.getFieldName();
                if (fields.length > i) {
                    String value = fields[i];
                    if (value == null || value.isEmpty()) {
                        if (type == TypeTags.UNION_TAG) {
                            List<Type> members = ((UnionType) internalStructField.getFieldType()).getMemberTypes();
                            if (TypeUtils.getReferredType(members.get(1)).getTag() == TypeTags.NULL_TAG) {
                                struct.put(fieldName, null);
                                continue;
                            } 
                            return IOUtils.createError("Unsupported nillable field : " + fieldName);
                        }
                        return IOUtils.createError("Field '" + fieldName + "' does not support nil value.");
                    } 
                    if (type == TypeTags.UNION_TAG) {
                        List<Type> members = ((UnionType) internalStructField.getFieldType()).getMemberTypes();
                        if (TypeUtils.getReferredType(members.get(1)).getTag() == TypeTags.NULL_TAG) {
                            type = TypeUtils.getReferredType(members.get(0)).getTag();
                        } else {
                            return IOUtils.createError("Unsupported nillable field : " 
                                + fieldName + " for value: " + value);
                        }
                    }
                    String trimmedValue = value.trim();
                    try {
                        switch (type) {
                            case TypeTags.INT_TAG:
                                struct.put(fieldName, Long.parseLong(trimmedValue));
                                break;
                            case TypeTags.FLOAT_TAG:
                                struct.put(fieldName, Double.parseDouble(trimmedValue));
                                break;
                            case TypeTags.STRING_TAG:
                                struct.put(fieldName, trimmedValue);
                                break;
                            case TypeTags.DECIMAL_TAG:
                                struct.put(fieldName, ValueCreator.createDecimalValue(trimmedValue));
                                break;
                            case TypeTags.BOOLEAN_TAG:
                                struct.put(fieldName, Boolean.parseBoolean(trimmedValue));
                                break;
                            default:
                                return IOUtils.createError(
                                    "Data mapping support only for int, float, Decimal, boolean and string. "
                                            + "Unsupported value for the struct field: " + fieldName);
                        }
                    } catch (NumberFormatException e) {
                        return IOUtils.createError(
                                    "Invalid value: " + trimmedValue + " for the field: '" + fieldName + "'");
                    }
                }
            }
            return struct;
        }
        return IOUtils.createError("Empty line detected");
    }
}
