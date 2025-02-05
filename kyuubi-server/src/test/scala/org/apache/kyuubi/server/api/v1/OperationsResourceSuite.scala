/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.api.v1

import javax.ws.rs.client.{Entity, WebTarget}
import javax.ws.rs.core.MediaType

import org.apache.hive.service.rpc.thrift.TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V2

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.events.KyuubiOperationEvent
import org.apache.kyuubi.operation.{ExecuteStatement, GetCatalogs, OperationState, OperationType}
import org.apache.kyuubi.operation.OperationType.OperationType
import org.apache.kyuubi.server.KyuubiRestFrontendService

class OperationsResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  test("test get an operation event") {
    withKyuubiRestServer { (fe, _, _, webTarget) =>
      val catalogsHandleStr = getOpHandleStr(fe, OperationType.GET_CATALOGS)
      var response = webTarget.path(s"api/v1/operations/$catalogsHandleStr/event")
        .request(MediaType.APPLICATION_JSON_TYPE).get()
      val operationEvent = response.readEntity(classOf[KyuubiOperationEvent])
      assert(200 == response.getStatus)
      assert(operationEvent.state == OperationState.INITIALIZED.name())

      val statementHandleStr = getOpHandleStr(fe, OperationType.EXECUTE_STATEMENT)
      response = webTarget.path(s"api/v1/operations/$statementHandleStr/event")
        .request(MediaType.APPLICATION_JSON_TYPE).get()
      val statementEvent = response.readEntity(classOf[KyuubiOperationEvent])
      assert(200 == response.getStatus)
      assert(statementEvent.state == OperationState.INITIALIZED.name())

      // Invalid operationHandleStr
      val invalidOperationHandle =
        statementHandleStr.replaceAll("EXECUTE_STATEMENT", "GET_TYPE_INFO")
      response = webTarget.path(s"api/v1/operations/$invalidOperationHandle/event")
        .request(MediaType.APPLICATION_JSON_TYPE).get()
      assert(404 == response.getStatus)
    }
  }

  test("test apply an action for an operation") {
    withKyuubiRestServer { (fe, _, _, webTarget: WebTarget) =>
      val opHandleStr = getOpHandleStr(fe, OperationType.EXECUTE_STATEMENT)

      var response = webTarget.path(s"api/v1/operations/$opHandleStr")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .put(Entity.entity(OpActionRequest("cancel"), MediaType.APPLICATION_JSON_TYPE))
      assert(200 == response.getStatus)

      response = webTarget.path(s"api/v1/operations/$opHandleStr/event")
        .request(MediaType.APPLICATION_JSON_TYPE).get()
      val operationEvent = response.readEntity(classOf[KyuubiOperationEvent])
      assert(operationEvent.state == OperationState.FINISHED.name() ||
        operationEvent.state == OperationState.CANCELED.name())

      response = webTarget.path(s"api/v1/operations/$opHandleStr")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .put(Entity.entity(OpActionRequest("close"), MediaType.APPLICATION_JSON_TYPE))
      assert(200 == response.getStatus)

      response = webTarget.path(s"api/v1/operations/$opHandleStr/event")
        .request(MediaType.APPLICATION_JSON_TYPE).get()
      assert(404 == response.getStatus)

    }
  }

  def getOpHandleStr(fe: KyuubiRestFrontendService, typ: OperationType): String = {
    val sessionManager = fe.be.sessionManager
    val sessionHandle = sessionManager.openSession(
      HIVE_CLI_SERVICE_PROTOCOL_V2,
      "admin",
      "123456",
      "localhost",
      Map("testConfig" -> "testValue"))
    val session = sessionManager.getSession(sessionHandle)

    val op = typ match {
      case OperationType.EXECUTE_STATEMENT =>
        new ExecuteStatement(session, "show tables", true, 3000)
      case OperationType.GET_CATALOGS =>
        new GetCatalogs(session)
    }
    sessionManager.operationManager.addOperation(op)
    val operationHandle = op.getHandle

    s"${operationHandle.identifier.publicId}|" +
      s"${operationHandle.identifier.secretId}|${operationHandle.protocol.getValue}|" +
      s"${operationHandle.typ.toString}"
  }
}
