/**
 * Defines the Ditto Protocol message which is understood by Eclipse Ditto.
 * @typedef {Object} DittoProtocolMessage
 * @property {string} topic - The topic of the Ditto Protocol message
 * @property {string} path - The path containing the info what to change / what changed
 * @property {Object.<string, string>} headers - The Ditto headers
 * @property {*} value - The value to change to / changed value
 */

/**
 * Defines an external (not yet mapped) message.
 * @typedef {Object} ExternalMessage
 * @property {Object.<string, string>} headers - The external headers
 * @property {string} [textPayload] - The String to be mapped
 * @property {Array<byte>} [bytePayload] - The bytes to be mapped
 * @property {string} contentType - The external Content-Type, e.g. "application/json"
 */

/**
 * Defines the Ditto scope containing helper methods.
 *
 * @type {{buildDittoProtocolMsg, buildExternalMsg}}
 */
let Ditto = (function () {
    /**
     * Builds a Ditto Protocol message from the passed parameters.
     * @param {string} namespace - The namespace of the entity in java package notation, e.g.: "org.eclipse.ditto"
     * @param {string} id - The ID of the entity
     * @param {string} group - The affected group/entity, one of: "things"
     * @param {string} channel - The channel for the signal, one of: "twin"|"live"
     * @param {string} criterion - The criterion to apply, one of: "commands"|"events"|"search"|"messages"|"errors"
     * @param {string} action - The action to perform, one of: "create"|"retrieve"|"modify"|"delete"
     * @param {string} path - The path which is affected by the message, e.g.: "/attributes"
     * @param {Object.<string, string>} dittoHeaders - The headers Object containing all Ditto Protocol header values
     * @param {*} [value] - The value to apply / which was applied (e.g. in a "modify" action)
     * @returns {DittoProtocolMessage} dittoProtocolMessage - the mapped Ditto Protocol message or <code>null</code> if the
     * message could/should not be mapped
     */
    let buildDittoProtocolMsg = function(namespace, id, group, channel, criterion, action, path, dittoHeaders, value) {

        let dittoProtocolMsg = {};
        dittoProtocolMsg.topic = namespace + "/" + id + "/" + group + "/" + channel + "/" + criterion + "/" + action;
        dittoProtocolMsg.path = path;
        dittoProtocolMsg.headers = dittoHeaders;
        dittoProtocolMsg.value = value;
        return dittoProtocolMsg;
    };

    /**
     * Builds an external message from the passed parameters.
     * @param {Object.<string, string>} headers - The external headers Object containing header values
     * @param {string} [textPayload] - The external mapped String
     * @param {string} [bytePayload] - The external mapped byte[]
     * @param {string} contentType - The returned Content-Type
     * @returns {ExternalMessage} externalMessage - the mapped external message or <code>null</code> if the
     * message could/should not be mapped
     */
    let buildExternalMsg = function(headers, textPayload, bytePayload, contentType) {

        let externalMsg = {};
        externalMsg.headers = headers;
        externalMsg.textPayload = textPayload;
        externalMsg.bytePayload = bytePayload;
        externalMsg.contentType = contentType;
        return externalMsg;
    };

    return {
        buildDittoProtocolMsg: buildDittoProtocolMsg,
        buildExternalMsg: buildExternalMsg
    }
})();
