(ns httpeek.handler
  (:use compojure.core)
  (:require [ring.util.response :as response]
            [ring.middleware.session :as session]
            [ring.middleware.params :as params]
            [ring.middleware.json :as ring-json]
            [schema.core :as s]
            [clojure.data.xml :as xml]
            [compojure.route :as route]
            [httpeek.core :as core]
            [cheshire.core :as json]
            [httpeek.views :as views]))

(defn- str->uuid [uuid-string]
  (core/with-error-handling nil
                            (java.util.UUID/fromString uuid-string)))

(defn- handle-web-inspect-bin [id session {:strs [host] :as headers}]
  (let [requested-bin (core/find-bin-by-id id)
        private? (:private requested-bin)
        permitted? (some #{id} (:private-bins session))]

    (if requested-bin
      (if (and private? (not permitted?))
        (response/status (response/response "private bin") 403)
        (views/inspect-bin-page id host (core/get-requests id)))
      (response/not-found (views/not-found-page)))))

(defn- slurp-body [request]
  (if (:body request)
    (update request :body slurp)
    request))

(defn- parse-request-to-bin [request]
  (let [request (slurp-body request)
        id (str->uuid (get-in request [:params :id]))
        body (json/encode request {:pretty true})
        requested-bin (core/find-bin-by-id id)]
    {:requested-bin requested-bin
     :body body}))

(defn- add-request-to-bin [id request-body]
  (core/add-request id request-body)
  (:response (core/find-bin-by-id id)))

(defn- route-request-to-bin [{:keys [requested-bin body] :as parsed-request}]
  (if-let [bin-id (:id requested-bin)]
    (add-request-to-bin bin-id body)
    (response/not-found (views/not-found-page))))

(defn- get-headers [header-names header-values]
  (if (or (nil? header-names) (nil? header-values))
    {}
    (zipmap (map #(name %) header-names)
            (map #(name %) header-values))))

(defn- create-custom-response [form-params]
  (let [status (read-string (get form-params "status"))
        headers (get-headers (clojure.edn/read-string (get form-params "header-name[]"))
                             (clojure.edn/read-string (get form-params "header-value[]")))]
    (json/encode {:status status
                  :headers headers
                  :body ""})))

(defn- handle-web-create-bin [req]
  (let [form-params (:form-params req)
        private? (boolean (get form-params "private-bin"))
        custom-response (create-custom-response form-params)
        bin-id (core/create-bin {:private private?} custom-response)]
  (if private?
      (-> (response/redirect (format "/bin/%s/inspect" bin-id))
        (assoc-in [:session] {:private-bins [bin-id]}))
      (response/redirect (format "/bin/%s/inspect" bin-id)))))

(defn- handle-web-delete-bin [id]
  (let [bin-id (str->uuid id)
        delete-count (core/delete-bin bin-id)]
    (if (< 0 delete-count)
      (response/redirect "/" 302)
      (response/not-found (views/not-found-page)))))

(defn- handle-web-request-to-bin [request]
  (-> request
    (parse-request-to-bin)
    (route-request-to-bin)))

(defn- handle-api-not-found [message]
  (response/not-found {:message message}))

(defn- handle-api-get-bin [id]
  (prn (core/find-bin-by-id (str->uuid id)))
  (if-let [bin (core/find-bin-by-id (str->uuid id))]
    (:response bin)
    (handle-api-not-found (format "The bin %s does not exist" id))))

(def response-map-skeleton
  {(s/required-key :status) s/Int
   (s/required-key :headers) {s/Keyword s/Str}
   (s/optional-key :body) s/Str})

(defn- validate-response-map [body]
  (s/validate response-map-skeleton body))

(defn- get-response-data-from-body [body]
    (validate-response-map body))

(defn- response-config [body]
  (core/with-error-handling (response/response 400)
                            (get-response-data-from-body body)))

(defn- handle-api-create-bin [body {:strs [host] :as headers}]
  (let [response (response-config body)
        bin-id (core/create-bin {:private false} response)]
    (response/response {:bin-url (format "http://%s/bin/%s" host bin-id)
                        :inspect-url (format "http://%s/bin/%s/inspect" host bin-id)
                        :delete-url (format "http://%s/bin/%s/delete" host bin-id)})))

(defn- handle-api-delete-bin [id]
  (let [bin-id (str->uuid id)
        delete-count (core/delete-bin bin-id)]
    (if (< 0 delete-count)
      (response/response {:message (str "bin" bin-id "has been deleted")})
      (handle-api-not-found (format "The bin %s could not be deleted because it doesn't exist" id)))))

(defn- handle-api-inspect-bin [id]
  (if-let [bin-id (:id (core/find-bin-by-id id))]
    (response/response {:bin-id bin-id
                        :requests (core/get-requests bin-id)})
    (handle-api-not-found (format "The bin %s could not be found" id))))

(defn- handle-api-bin-index []
  (response/response {:bins (core/get-bins {:limit 50})}))

(defroutes api-routes
  (context "/api" []
    (GET "/" [] (handle-api-bin-index))
    (GET "/bin/:id/inspect" [id] (handle-api-inspect-bin id))
    (DELETE "/bin/:id/delete" [id] (handle-api-delete-bin id))
    (POST "/bins" {body :body headers :headers} (handle-api-create-bin body headers))
    (route/not-found (handle-api-not-found "This resource could not be found"))))

(defroutes web-routes
  (GET "/" [] (views/index-page))
  (POST "/bins" req (-> req
                      params/params-request
                      handle-web-create-bin))
  (GET "/bin/:id/inspect" [id :as {session :session headers :headers}] (handle-web-inspect-bin (str->uuid id) session headers))
  (ANY "/bin/:id" req (handle-web-request-to-bin req))
  (POST "/bin/:id/delete" [id] (handle-web-delete-bin id))
  (route/resources "/")
  (route/not-found (views/not-found-page)))

(def app*
  (routes (-> api-routes
            (ring-json/wrap-json-body {:keywords? true})
            (ring-json/wrap-json-response))
          (-> web-routes
            (session/wrap-session))))
