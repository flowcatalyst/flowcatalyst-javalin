<?php

namespace FlowCatalyst\Generated\Model;

class DeliveryPlan
{
    /**
     * @var array
     */
    protected $initialized = [];
    public function isInitialized($property): bool
    {
        return array_key_exists($property, $this->initialized);
    }
    /**
     * A URL to the JSON Schema for this object.
     *
     * @var string|null
     */
    protected $dollarSchema;
    /**
     * @var string|null
     */
    protected $body;
    /**
     * @var array<string, string>|null
     */
    protected $headers;
    /**
     * @var RequestSummary|null
     */
    protected $request;
    /**
     * A URL to the JSON Schema for this object.
     *
     * @return string|null
     */
    public function getDollarSchema(): ?string
    {
        return $this->dollarSchema;
    }
    /**
     * A URL to the JSON Schema for this object.
     *
     * @param string|null $dollarSchema
     *
     * @return self
     */
    public function setDollarSchema(?string $dollarSchema): self
    {
        $this->initialized['dollarSchema'] = true;
        $this->dollarSchema = $dollarSchema;
        return $this;
    }
    /**
     * @return string|null
     */
    public function getBody(): ?string
    {
        return $this->body;
    }
    /**
     * @param string|null $body
     *
     * @return self
     */
    public function setBody(?string $body): self
    {
        $this->initialized['body'] = true;
        $this->body = $body;
        return $this;
    }
    /**
     * @return array<string, string>|null
     */
    public function getHeaders(): ?iterable
    {
        return $this->headers;
    }
    /**
     * @param array<string, string>|null $headers
     *
     * @return self
     */
    public function setHeaders(?iterable $headers): self
    {
        $this->initialized['headers'] = true;
        $this->headers = $headers;
        return $this;
    }
    /**
     * @return RequestSummary|null
     */
    public function getRequest(): ?RequestSummary
    {
        return $this->request;
    }
    /**
     * @param RequestSummary|null $request
     *
     * @return self
     */
    public function setRequest(?RequestSummary $request): self
    {
        $this->initialized['request'] = true;
        $this->request = $request;
        return $this;
    }
}