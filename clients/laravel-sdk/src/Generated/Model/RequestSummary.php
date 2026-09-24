<?php

namespace FlowCatalyst\Generated\Model;

class RequestSummary
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
     * @var bool|null
     */
    protected $bearer;
    /**
     * @var list<string>|null
     */
    protected $headers;
    /**
     * @var bool|null
     */
    protected $signature;
    /**
     * @var string|null
     */
    protected $signedBy;
    /**
     * @var string|null
     */
    protected $target;
    /**
     * @var string|null
     */
    protected $timestamp;
    /**
     * @var string|null
     */
    protected $unsignedReason;
    /**
     * @return bool|null
     */
    public function getBearer(): ?bool
    {
        return $this->bearer;
    }
    /**
     * @param bool|null $bearer
     *
     * @return self
     */
    public function setBearer(?bool $bearer): self
    {
        $this->initialized['bearer'] = true;
        $this->bearer = $bearer;
        return $this;
    }
    /**
     * @return list<string>|null
     */
    public function getHeaders(): ?array
    {
        return $this->headers;
    }
    /**
     * @param list<string>|null $headers
     *
     * @return self
     */
    public function setHeaders(?array $headers): self
    {
        $this->initialized['headers'] = true;
        $this->headers = $headers;
        return $this;
    }
    /**
     * @return bool|null
     */
    public function getSignature(): ?bool
    {
        return $this->signature;
    }
    /**
     * @param bool|null $signature
     *
     * @return self
     */
    public function setSignature(?bool $signature): self
    {
        $this->initialized['signature'] = true;
        $this->signature = $signature;
        return $this;
    }
    /**
     * @return string|null
     */
    public function getSignedBy(): ?string
    {
        return $this->signedBy;
    }
    /**
     * @param string|null $signedBy
     *
     * @return self
     */
    public function setSignedBy(?string $signedBy): self
    {
        $this->initialized['signedBy'] = true;
        $this->signedBy = $signedBy;
        return $this;
    }
    /**
     * @return string|null
     */
    public function getTarget(): ?string
    {
        return $this->target;
    }
    /**
     * @param string|null $target
     *
     * @return self
     */
    public function setTarget(?string $target): self
    {
        $this->initialized['target'] = true;
        $this->target = $target;
        return $this;
    }
    /**
     * @return string|null
     */
    public function getTimestamp(): ?string
    {
        return $this->timestamp;
    }
    /**
     * @param string|null $timestamp
     *
     * @return self
     */
    public function setTimestamp(?string $timestamp): self
    {
        $this->initialized['timestamp'] = true;
        $this->timestamp = $timestamp;
        return $this;
    }
    /**
     * @return string|null
     */
    public function getUnsignedReason(): ?string
    {
        return $this->unsignedReason;
    }
    /**
     * @param string|null $unsignedReason
     *
     * @return self
     */
    public function setUnsignedReason(?string $unsignedReason): self
    {
        $this->initialized['unsignedReason'] = true;
        $this->unsignedReason = $unsignedReason;
        return $this;
    }
}