/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.TlsContextManager;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.Executor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link SslContextProviderSupplier}.
 */
@RunWith(JUnit4.class)
public class SslContextProviderSupplierTest {

  @Mock private TlsContextManager mockTlsContextManager;
  private SslContextProviderSupplier supplier;
  private SslContextProvider mockSslContextProvider;
  private EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext;
  private SslContextProvider.Callback mockCallback;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  private void prepareSupplier() {
    upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe", true);
    mockSslContextProvider = mock(SslContextProvider.class);
    doReturn(mockSslContextProvider)
            .when(mockTlsContextManager)
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    supplier = new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager);
  }

  private void callUpdateSslContext() {
    mockCallback = mock(SslContextProvider.Callback.class);
    Executor mockExecutor = mock(Executor.class);
    doReturn(mockExecutor).when(mockCallback).getExecutor();
    supplier.updateSslContext(mockCallback);
  }

  @Test
  public void get_updateSecret() {
    prepareSupplier();
    callUpdateSslContext();
    verify(mockTlsContextManager, times(2))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    verify(mockTlsContextManager, times(0))
        .releaseClientSslContextProvider(any(SslContextProvider.class));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor = ArgumentCaptor.forClass(null);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSecret(mockSslContext);
    verify(mockCallback, times(1)).updateSecret(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    supplier.updateSslContext(mockCallback);
    verify(mockTlsContextManager, times(3))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
  }

  @Test
  public void get_onException() {
    prepareSupplier();
    callUpdateSslContext();
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor = ArgumentCaptor.forClass(null);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    capturedCallback.onException(new Exception("test"));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider));
  }

  @Test
  public void testClose() {
    prepareSupplier();
    callUpdateSslContext();
    supplier.close();
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    SslContextProvider.Callback mockCallback = spy(
        new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
          @Override
          public void updateSecret(SslContext sslContext) {
            Assert.fail("unexpected call");
          }

          @Override
          protected void onException(Throwable argument) {
            assertThat(argument).isInstanceOf(IllegalStateException.class);
            assertThat(argument).hasMessageThat().contains("Supplier is shutdown!");
          }
        });
    supplier.updateSslContext(mockCallback);
  }

  @Test
  public void testClose_nullSslContextProvider() {
    prepareSupplier();
    doThrow(new NullPointerException()).when(mockTlsContextManager)
        .releaseClientSslContextProvider(null);
    supplier.close();
    verify(mockTlsContextManager, never())
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    SslContextProvider.Callback mockCallback = spy(
        new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
          @Override
          public void updateSecret(SslContext sslContext) {
            Assert.fail("unexpected call");
          }

          @Override
          protected void onException(Throwable argument) {
            assertThat(argument).isInstanceOf(IllegalStateException.class);
            assertThat(argument).hasMessageThat().contains("Supplier is shutdown!");
          }
        });
    supplier.updateSslContext(mockCallback);
  }
}
