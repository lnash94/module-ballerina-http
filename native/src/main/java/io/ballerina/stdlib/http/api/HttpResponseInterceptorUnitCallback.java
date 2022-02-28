/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.stdlib.http.api;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.stdlib.http.api.nativeimpl.connection.Respond;
import io.ballerina.stdlib.http.transport.message.HttpCarbonMessage;

/**
 * {@code HttpResponseInterceptorUnitCallback} is the responsible for acting on notifications received from Ballerina
 * side when a response interceptor service is invoked.
 *
 * @since SL Beta 4
 */
public class HttpResponseInterceptorUnitCallback implements Callback {
    private final HttpCarbonMessage requestMessage;
    private final BObject caller;
    private final BObject response;
    private final Environment environment;
    private final BObject requestCtx;
    private DataContext dataContext;

    private static final String ILLEGAL_FUNCTION_INVOKED = "illegal return: response has already been sent";

    public HttpResponseInterceptorUnitCallback(HttpCarbonMessage requestMessage, BObject caller, BObject response,
                                               Environment env, DataContext dataContext) {
        this.requestMessage = requestMessage;
        this.requestCtx = (BObject) requestMessage.getProperty(HttpConstants.REQUEST_CONTEXT);
        this.caller = caller;
        this.response = response;
        this.environment = env;
        this.dataContext = dataContext;
    }

    @Override
    public void notifySuccess(Object result) {
        printStacktraceIfError(result);
        if (result instanceof BError) {
            notifyFailure((BError) result);
        } else {
            validateResponseAndProceed(result);
        }
    }

    @Override
    public void notifyFailure(BError error) { // handles panic and check_panic
        // This check is added to release the failure path since there is an authn/authz failure and responded
        // with 401/403 internally.
        if (error.getMessage().equals("Already responded by auth desugar.")) {
            return;
        }
        sendFailureResponse(error);
    }

    public void sendFailureResponse(BError error) {
        cleanupResponseAndContext();
        HttpUtil.handleFailure(requestMessage, error, false);
    }

    private void cleanupResponseAndContext() {
        requestMessage.waitAndReleaseAllEntities();
    }

    private void printStacktraceIfError(Object result) {
        if (result instanceof BError) {
            ((BError) result).printStackTrace();
        }
    }

    private void sendResponseToNextService() {
        Respond.nativeRespondWithDataCtx(environment, caller, response, dataContext);
    }

    private boolean alreadyResponded() {
        try {
            HttpUtil.methodInvocationCheck(requestMessage, HttpConstants.INVALID_STATUS_CODE, ILLEGAL_FUNCTION_INVOKED);
        } catch (BError e) {
            return true;
        }
        return false;
    }

    private void validateResponseAndProceed(Object result) {
        int interceptorId = (int) requestCtx.getNativeData(HttpConstants.RESPONSE_INTERCEPTOR_INDEX);
        requestMessage.setProperty(HttpConstants.RESPONSE_INTERCEPTOR_INDEX, interceptorId);
        BArray interceptors = (BArray) requestCtx.getNativeData(HttpConstants.INTERCEPTORS);
        boolean nextCalled = (boolean) requestCtx.getNativeData(HttpConstants.REQUEST_CONTEXT_NEXT);

        if (alreadyResponded()) {
            if (nextCalled) {
                sendResponseToNextService();
            }
            return;
        }

        if (result == null) {
            requestMessage.setProperty(HttpConstants.RESPONSE_INTERCEPTOR_INDEX, 0);
            sendResponseToNextService();
            return;
        }

        if (interceptors != null) {
            if (interceptorId < interceptors.size()) {
                Object interceptor = interceptors.get(interceptorId);
                if (result.equals(interceptor)) {
                    sendResponseToNextService();
                } else {
                    BError err = HttpUtil.createHttpError("next interceptor service did not match " +
                            "with the configuration", HttpErrorType.GENERIC_LISTENER_ERROR);
                    sendFailureResponse(err);
                }
            }
        }
    }
}
