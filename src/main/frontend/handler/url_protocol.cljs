(ns frontend.handler.url-protocol
  (:require [clojure.string :as string]
            [frontend.state :as state]
            [frontend.db :as db]
            [frontend.db.model :as db-model]
            [frontend.handler.editor :as editor-handler]
            [frontend.util.url :as url-util]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.util.drawer :as drawer]
            [frontend.util.property :as property]))

(defn quick-capture
  [url title content page append]
  (let [insert-today? (get-in (state/get-config)
                              [:quick-capture-options :insert-today?]
                              false)
        redirect-page? (get-in (state/get-config)
                               [:quick-capture-options :redirect-page?]
                               false)
        today-page (when (state/enable-journals?)
                     (string/lower-case (date/today)))
        page (if (or (= page "TODAY")
                     (and (string/blank? page) insert-today?))
               today-page
               (or (not-empty page)
                   (state/get-current-page)
                   today-page))
        page (or page "quick capture") ;; default to quick capture page, if journals are not enabled
        format (db/get-page-format page)
        time (date/get-current-time)
        text (or (and content (not-empty (string/trim content))) "")
        link (if (string/includes? url "www.youtube.com/watch")
               (str title " {{video " url "}}")
               (if (not-empty title)
                 (config/link-format format title url)
                 url))
        template (get-in (state/get-config)
                         [:quick-capture-templates :text]
                         "**{time}** [[quick capture]]: {text} {url}")
        content (-> template
                    (string/replace "{time}" time)
                    (string/replace "{url}" link)
                    (string/replace "{text}" text))
        edit-content (state/get-edit-content)
        edit-content-blank? (string/blank? edit-content)
        edit-content-include-capture? (and (not-empty edit-content)
                                           (string/includes? edit-content "[[quick capture]]"))]
    (if (and (state/editing?) (not append) (not edit-content-include-capture?))
      (if edit-content-blank?
        (editor-handler/insert content)
        (editor-handler/insert (str "\n" content)))

      (do
        (editor-handler/escape-editing)
        (when (not= page (state/get-current-page))
          (page-handler/create! page {:redirect? redirect-page?}))
        ;; Or else this will clear the newly inserted content
        (js/setTimeout #(editor-handler/api-insert-new-block! content {:page page
                                                                       :edit-block? true
                                                                       :replace-empty-target? true})
                       100)))))

(defn get-current-url 
  [x-success x-error?]
  ;; Compatible with https://hookproductivity.com/help/integration/information-for-developers-api-requirements/x-callback-url/
  ;; Might be extended to support other hooking demands in the future.
  (let [repo (state/get-current-repo)
                             ;; may reuse the following logic for other api services
        edit-block-id   (some-> (state/get-edit-block) (:block/uuid))
        select-block-id (first (state/get-selection-block-ids))
        cur-page-name   (state/get-current-page)
        block-uuid      (or edit-block-id select-block-id)
        block-entity    (when block-uuid (db-model/get-block-by-uuid block-uuid))
        ;; TODO unify the get readable content functions
        block-content   (when block-entity (some->> block-entity
                                                   (:block/content)
                                                   (drawer/remove-logbook)
                                                   (property/remove-properties (:block/format block-entity))))

        block-url       (when block-uuid
                          (editor-handler/set-blocks-id! [block-uuid])
                          (url-util/get-logseq-graph-uuid-url nil repo block-uuid))
        page-url      (when cur-page-name (url-util/get-logseq-graph-page-url nil repo cur-page-name))
        page-title    (when cur-page-name (db-model/get-page-original-name cur-page-name))
        page-file     (when cur-page-name (:file/path (db-model/get-page-file cur-page-name)))
        url     (or block-url page-url) ;; block has higher priority
        title   page-title              ;; only use page title
        file    page-file               ;; only use page file
        content block-content           ;; TODO: page content preview
        target  (if (and x-error? (nil? url))
                  x-error?
                  (str x-success
                       "url=" (url-util/encode-param url)
                       (when title   (str "&title="   (url-util/encode-param title)))
                       (when content (str "&content=" (url-util/encode-param content)))
                       (when file    (str "&file="    (url-util/encode-param file)))))]
    (js/setTimeout #(js/window.open target) 100) ;; Magic, don't remove the timeout, even 1ms works. Why?
    ))

