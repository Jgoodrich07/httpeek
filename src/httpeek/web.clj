(ns httpeek.web
  (:use compojure.core)
  (:require [ring.util.response :as response]
            [ring.middleware.params :as params]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [compojure.route :as route]
            [httpeek.core :as core]
            [cheshire.core :as json]
            [httpeek.views.layouts :as views])
  (:import java.io.ByteArrayInputStream))

(extend-protocol cheshire.generate/JSONable
  org.joda.time.DateTime
  (to-json [t jg]
    (cheshire.generate/write-string jg (str t))))

(defn- handle-web-inspect-bin [id session {:strs [host] :as headers}]
  (let [requested-bin (core/find-bin-by-id id)
        private? (:private requested-bin)
        permitted? (some #{id} (:private-bins session))]
    (if requested-bin
      (if (and private? (not permitted?))
        (response/status (response/response "private bin") 403)
        (views/inspect-bin-page id host (core/get-requests id)))
      (response/not-found views/not-found-page))))

(defn- slurp-body [request]
  (if (:body request)
    (update request :body slurp)
    request))

(defn- parse-request-to-bin [request]
  (let [request (slurp-body request)
        id (core/str->uuid (get-in request [:params :id]))
        body (json/encode request {:pretty true})
        requested-bin (core/find-bin-by-id id)]
    {:requested-bin requested-bin
     :body body}))

(defn- add-request-to-bin [id request-body]
  (core/add-request id request-body)
  (let [response (:response (core/find-bin-by-id id))
        body (ByteArrayInputStream. (.getBytes (:body response)))]
    (-> (update response :headers walk/stringify-keys)
      (assoc :body body))))

(defn- route-request-to-bin [{:keys [requested-bin body] :as parsed-request}]
  (if-let [bin-id (:id requested-bin)]
    (add-request-to-bin bin-id body)
    (response/not-found views/not-found-page)))

(defn- str->vector [input]
  (if (string? input)
    (vector input)
    input))

(defn- normalize-headers [partial-header]
  (->> partial-header
    (str->vector)
   (remove empty?)))

(defn- create-headers [header-names header-values]
  (let [header-names (normalize-headers header-names)
        header-values (normalize-headers header-values)]
    (if (= (count header-names) (count header-values))
      (zipmap (map #(name %) header-names)
              (map #(name %) header-values))
      nil)))

(def errors (atom #{}))

(defn- create-bin-response [form-params]
  (let [status (edn/read-string (get form-params "status"))
        headers (create-headers (get form-params "header-name[]")
                                (get form-params "header-value[]"))
        body (get form-params "body")
        response-map {:status status
                      :headers headers
                      :body body}
        response-errors (core/validate-response response-map)]
    (if (empty? response-errors)
      response-map
      (swap! errors clojure.set/union response-errors))))

(defn- get-time-till-exp [hours]
  (if-let [hours-till-exp (edn/read-string hours)]
    (let [exp-error (core/validate-expiration {:time-to-expiration hours-till-exp})]
      (if (empty? exp-error)
        hours-till-exp
        (swap! errors clojure.set/union exp-error)))
    24))

(defn- private-bin-response [bin-id]
  (-> (response/redirect (format "/bin/%s/inspect" bin-id))
    (assoc-in [:session] {:private-bins [bin-id]})))

(defn- public-bin-response [bin-id]
  (response/redirect (format "/bin/%s/inspect" bin-id)))

(defn- user-error-response []
      (-> (response/redirect "/")
        (assoc :flash @errors)))

(defn- handle-web-create-bin [req]
  (let [form-params (:form-params req)
        time-till-exp (get-time-till-exp (get form-params "expiration"))
        private? (boolean (get form-params "private-bin"))
        bin-response (create-bin-response form-params)]
    (if-let [bin-id (core/create-bin {:private private?
                                      :response bin-response
                                      :time-to-expiration time-till-exp})]
      (if private?
        (private-bin-response bin-id)
        (public-bin-response bin-id))
      (user-error-response))))

(defn- handle-web-delete-bin [id]
  (let [bin-id (core/str->uuid id)
        delete-count (core/delete-bin bin-id)]
    (if (< 0 delete-count)
      (response/redirect "/")
      (response/not-found views/not-found-page))))

(defn- handle-web-request-to-bin [request]
  (-> request
    (parse-request-to-bin)
    (route-request-to-bin)))

(defroutes web-routes
  (GET "/" {flash :flash} (views/index-page flash))
  (POST "/bins" req (-> req
                      params/params-request
                      handle-web-create-bin))
  (GET "/bin/:id/inspect" [id :as {session :session headers :headers}] (handle-web-inspect-bin
                                                                         (core/str->uuid id) session headers))
  (ANY "/bin/:id" req (handle-web-request-to-bin req))
  (POST "/bin/:id/delete" [id] (handle-web-delete-bin id))
  (route/resources "/")
  (route/not-found views/not-found-page))
