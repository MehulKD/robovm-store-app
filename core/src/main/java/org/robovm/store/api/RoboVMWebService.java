/*
 * Copyright (C) 2013-2015 RoboVM AB
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 *   
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.robovm.store.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.robovm.store.api.RoboVMWebService.ActionWrapper.ActionWrapperImpl;
import org.robovm.store.model.Order;
import org.robovm.store.model.Product;
import org.robovm.store.model.User;
import org.robovm.store.util.Action;
import org.robovm.store.util.ImageCache;

import retrofit.Call;
import retrofit.Callback;
import retrofit.GsonConverterFactory;
import retrofit.Response;
import retrofit.Retrofit;
import retrofit.http.Body;
import retrofit.http.GET;
import retrofit.http.POST;

public class RoboVMWebService {
    private static final RoboVMWebService instance = new RoboVMWebService();

    private RoboVMWebService() {}

    public static RoboVMWebService getInstance() {
        return instance;
    }

    private static final String API_URL = "https://store-app.robovm.com/api/";
    private static final String API_TEST_URL = "https://store-app.robovm.com/test/";

    private Retrofit retrofit;
    private RoboVMAPI api;

    private AuthToken authToken;
    private User currentUser;
    private List<Product> products;
    private final List<Order> basket = new ArrayList<>();

    private ActionWrapper invokation = new ActionWrapperImpl();

    public RoboVMWebService setup() {
        return setup(false);
    }

    public RoboVMWebService setup(boolean test) {
        // Create a REST adapter which points to the RoboVM API.
        retrofit = new Retrofit.Builder()
                .baseUrl(test ? API_TEST_URL : API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        // Create an instance of our RoboVM API interface.
        api = retrofit.create(RoboVMAPI.class);

        return this;
    }

    public void authenticate(String username, String password, Action<Boolean> completion) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(completion, "completion");

        if (isAuthenticated()) {
            if (currentUser == null) {
                currentUser = new User();
            }
            invokation.invoke(completion, true);
        } else {
            api.auth(new AuthRequest(username, password)).enqueue(new Callback<AuthResponse>() {
                @Override
                public void onResponse(Response<AuthResponse> response, Retrofit retrofit) {
                    boolean success = false;
                    if (response.isSuccess()) {
                        AuthResponse body = response.body();
                        if (body.isSuccess()) {
                            success = true;
                            authToken = new AuthToken(body.getAuthToken(), () -> {
                                // Token timed out.
                                // TODO token timed out
                                });
                        }
                    }
                    if (success) {
                        currentUser = new User();
                    }
                    invokation.invoke(completion, success);
                }

                @Override
                public void onFailure(Throwable t) {
                    t.printStackTrace();
                    invokation.invoke(completion, false);
                }
            });
        }
    }

    public void getProducts(Action<List<Product>> completion) {
        Objects.requireNonNull(completion);

        if (products != null) {
            invokation.invoke(completion, products);
        } else {
            api.products().enqueue(new Callback<ProductsResponse>() {
                @Override
                public void onResponse(Response<ProductsResponse> response, Retrofit retrofit) {
                    List<Product> products = null;
                    if (response.isSuccess()) {
                        ProductsResponse body = response.body();
                        if (body.isSuccess()) {
                            products = body.getProducts();
                        }
                    }

                    RoboVMWebService.this.products = products;
                    if (products == null) {
                        // Return empty list in case of failure.
                        products = new ArrayList<>();
                    }
                    invokation.invoke(completion, products);
                }

                @Override
                public void onFailure(Throwable t) {
                    t.printStackTrace();

                    invokation.invoke(completion, new ArrayList<>());
                }
            });
        }
    }

    public void placeOrder(User user, Action<APIResponse> completion) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(completion);

        api.order(new OrderRequest(authToken, currentUser, basket)).enqueue(new Callback<APIResponse>() {
            @Override
            public void onResponse(Response<APIResponse> response, Retrofit retrofit) {
                if (response.isSuccess()) {
                    invokation.invoke(completion, response.body());
                } else {
                    invokation.invoke(completion, null);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                t.printStackTrace();
                invokation.invoke(completion, null);
            }
        });
    }

    public void preloadProductImages() {
        new Thread(() -> {
            for (Product product : products) {
                for (String url : product.getImageUrls()) {
                    ImageCache.getInstance().downloadImage(url);
                }
            }
        }).start();
    }

    public boolean isAuthenticated() {
        return authToken != null && !authToken.isExpired();
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void addToBasket(Order order) {
        basket.add(new Order(order));
    }

    public void clearBasket() {
        basket.clear();
    }

    public List<Order> getBasket() {
        return basket;
    }

    public RoboVMAPI getApi() {
        return api;
    }

    public void setActionWrapper(ActionWrapper wrapper) {
        this.invokation = wrapper;
    }

    public ActionWrapper getActionWrapper() {
        return invokation;
    }

    public interface RoboVMAPI {
        @POST("auth")
        Call<AuthResponse> auth(@Body AuthRequest body);

        @GET("products")
        Call<ProductsResponse> products();

        @POST("order")
        Call<APIResponse> order(@Body OrderRequest body);
    }

    public interface ActionWrapper {
        <T> void invoke(Action<T> action, T result);

        static class ActionWrapperImpl implements ActionWrapper {
            @Override
            public <T> void invoke(Action<T> action, T result) {
                action.invoke(result);
            }
        }
    }
}